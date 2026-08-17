package com.variocube.vcmp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.variocube.vcmp.ObjectMapperHolder.createObjectMapper;
import static org.springframework.util.StringUtils.hasText;

@Slf4j
public final class VcmpHandler implements WebSocketHandler {

    private final ObjectMapper objectMapper;

    private final Object target;
    private final HashMap<Class<?>, Listener> listeners;

    private final ConcurrentHashMap<String, VcmpSession> sessions = new ConcurrentHashMap<>();

    @Getter
    @Setter
    private Runnable disconnectHandler;

    /**
     * Optional semaphore bounding how many connect handlers run concurrently. Connect handlers
     * typically perform several DB operations; on a server, a fleet-wide reconnect storm would
     * otherwise exhaust the DB pool (variocube/center#427). Shared across all handlers of an
     * application so the bound covers all endpoints competing for the same resources.
     */
    @Setter
    private Semaphore connectThrottle;

    public static final int DEFAULT_LISTENER_RETRY_ATTEMPTS = 5;
    public static final long DEFAULT_LISTENER_RETRY_INITIAL_DELAY_MS = 100;

    /**
     * Bounded retry for listeners that opted in with {@code @VcmpListener(retry = true)}
     * (variocube/center#450): a transient failure — e.g. a lock conflict rolling back the
     * listener's transaction at commit — is retried with exponential backoff instead of
     * immediately NAKing, because a NAKed message is dropped and the sender may only replay
     * it on the next session connect. Total attempts including the first; {@code <= 1}
     * disables retry.
     */
    @Setter
    private int listenerRetryAttempts = DEFAULT_LISTENER_RETRY_ATTEMPTS;

    /**
     * Delay before the first retry; doubles on each subsequent retry (100, 200, 400, 800 ms
     * with the defaults, ~1.5s total budget).
     */
    @Setter
    private long listenerRetryInitialDelayMs = DEFAULT_LISTENER_RETRY_INITIAL_DELAY_MS;

    private record Listener(Method method, boolean retry) {
    }

    public VcmpHandler(Object target) {

        this.target = target;

        objectMapper = createObjectMapper();

        this.listeners = findListeners(target);
        this.listeners.keySet().forEach(objectMapper::registerSubtypes);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket session opened: {}", session.getId());

        VcmpSession vcmpSession = new VcmpSession(session, this);
        sessions.put(session.getId(), vcmpSession);

        Executor.getExecutor().submit(() -> {
            val throttle = this.connectThrottle;
            if (throttle != null) {
                throttle.acquireUninterruptibly();
            }
            try {
                MethodAnnotationUtils.invokeMethodWithAnnotation(this.target, VcmpSessionConnected.class, vcmpSession);
            }
            catch (Exception e) {
                log.error("Could not invoke connect handler", e);
            }
            finally {
                if (throttle != null) {
                    throttle.release();
                }
            }
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        log.info("WebSocket session {} closed: {}", session.getId(), closeStatus);
        val vcmpSession = sessions.remove(session.getId());
        if (vcmpSession != null) {
            // Notify senders and chained ACKs that their callbacks will never be acknowledged.
            // Handled asynchronously like every other handler dispatch: a chained NAK sends a
            // frame on another session (blocking socket I/O behind the send lock), and consumer
            // NAK handlers may block arbitrarily - neither may hold the container's I/O thread.
            Executor.getExecutor().submit(vcmpSession::failPendingCallbacks);
        }
        try {
            MethodAnnotationUtils.invokeMethodWithAnnotation(this.target, VcmpSessionDisconnected.class, vcmpSession);
        }
        catch (Exception e) {
            log.error("Could not invoke disconnect handler", e);
        }

        // Call disconnect handler
        if (disconnectHandler != null) {
            disconnectHandler.run();
        }
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
        if (log.isTraceEnabled()) {
            log.trace("Handling message {}", message);
        }

        VcmpSession vcmpSession = getSession(session);
        if (vcmpSession != null) {
            if (message instanceof TextMessage textMessage) {
                handleTextMessage(vcmpSession, textMessage);
            }
            else if (message instanceof BinaryMessage) {
                log.warn("Received binary message. This is unsupported.");
            }
            else if (message instanceof PongMessage) {
                log.error("Received pong message. This is no longer supported.");
            }
            else {
                throw new IllegalStateException("Unexpected WebSocket message type: " + message);
            }
        }
        else {
            log.error("Could not find VcmpSession for WebSocketSession: {}", session);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        // Transport errors are typically benign client disconnects (closed tab, dropped wifi,
        // proxy timeout). Log at WARN with the throwable so the cause is visible when it
        // matters, without paging on what is usually a normal lifecycle event.
        log.warn("Transport error in session {}", session.getId(), exception);
        if (session.isOpen()) {
            session.close();
        }
    }

    private void handleTextMessage(VcmpSession session, TextMessage textMessage) {
        if (textMessage.isLast()) {
            String payload = session.getMessageBuffer().flush(textMessage.getPayload());
            handlePayload(session, payload);
        }
        else {
            session.getMessageBuffer().append(textMessage.getPayload());
        }
    }

    private void handlePayload(VcmpSession session, String payload) {
        VcmpFrame frame = VcmpFrame.parse(payload);

        // Handle it asynchronously from here,
        // so that a handler cannot block the receiver thread.
        Executor.getExecutor().submit(() -> {
            switch (frame.getType()) {
                case ACK:
                    log.debug("Received ACK for {}.", frame.getId());
                    session.notifyAck(frame.getId(), frame.getPayload());
                    break;
                case NAK:
                    log.debug("Received NAK for {}.", frame.getId());
                    session.notifyNak(frame.getId(), parseProblemDetail(frame.getPayload()));
                    break;
                case MSG:
                    handleMessagePayload(session, frame.getId(), frame.getPayload());
                    break;
                case HBT:
                    session.handleHeartbeatReceived(frame);
                    break;
            }
        });
    }

    private void handleMessagePayload(VcmpSession session, String messageId, String messagePayload) {
        // Pre-flight: deserialization and listener lookup are deterministic — a failure here
        // can never be fixed by a retry, so it NAKs immediately.
        final VcmpMessage message;
        try {
            if (log.isTraceEnabled()) {
                log.trace("Handling message: {}", messageId);
            }
            message = objectMapper.readValue(messagePayload, VcmpMessage.class);
            if (!this.listeners.containsKey(message.getClass())) {
                throw new IllegalStateException("Could not find listener for " + message.getClass().getSimpleName());
            }
        }
        catch (Exception ex) {
            log.error("Error handling message {}", messageId, ex);
            nak(session, messageId, createProblemDetail(ex));
            return;
        }
        attemptInvocation(session, messageId, message, 1);
    }

    private void attemptInvocation(VcmpSession session, String messageId, VcmpMessage message, int attempt) {
        try {
            Object returnValue = invokeListener(session, message);

            VcmpCallback<?> callback = Optional.ofNullable(returnValue)
                    .filter(VcmpCallback.class::isInstance)
                    .map(VcmpCallback.class::cast)
                    .orElse(null);

            CompletableFuture<?> completableFuture = Optional.ofNullable(returnValue)
                    .filter(CompletableFuture.class::isInstance)
                    .map(CompletableFuture.class::cast)
                    .orElse(null);

            if (callback != null) {
                // The listener owns the outcome via its callback; a retry would double-invoke it.
                callback.onAck(result -> ack(session, messageId, result));
                callback.onNak(error -> nak(session, messageId, error));
            }
            else if (completableFuture != null) {
                completableFuture.thenAccept(result -> ack(session, messageId, result))
                        .exceptionally(error -> {
                            handleListenerFailure(session, messageId, message, attempt, unwrap(error));
                            return null;
                        });
            }
            else {
                // No callback provided. That means the listener succeeded synchronously.
                ack(session, messageId, returnValue);
            }
        }
        catch (InvocationTargetException ex) {
            handleListenerFailure(session, messageId, message, attempt, ex.getCause());
        }
        catch (Exception ex) {
            handleListenerFailure(session, messageId, message, attempt, ex);
        }
    }

    private void handleListenerFailure(VcmpSession session, String messageId, VcmpMessage message,
            int attempt, Throwable error) {
        val listener = this.listeners.get(message.getClass());
        boolean retryable = listener.retry() && !isFastFail(error);
        if (retryable && attempt < listenerRetryAttempts && session.isOpen()) {
            long delay = listenerRetryInitialDelayMs << (attempt - 1);
            // WARN with the full stack so recurring failure clusters stay greppable even
            // when retries absorb them.
            log.warn("Listener for {} failed on attempt {}/{}; retrying in {} ms",
                    message.getClass().getSimpleName(), attempt, listenerRetryAttempts, delay, error);
            Executor.getExecutor().schedule(
                    () -> attemptInvocation(session, messageId, message, attempt + 1),
                    delay, TimeUnit.MILLISECONDS);
        }
        else {
            log.error("Listener for {} failed after {} attempt(s). Sending NAK.",
                    message.getClass().getSimpleName(), attempt, error);
            nak(session, messageId, createProblemDetail(error));
        }
    }

    /**
     * Failures that resolve to a deliberate error status are never retried: the listener
     * chose that outcome, and delaying its NAK would break fast-fail semantics.
     */
    private static boolean isFastFail(Throwable error) {
        return error instanceof ErrorResponseException
                || AnnotatedElementUtils.findMergedAnnotation(error.getClass(), ResponseStatus.class) != null;
    }

    private static Throwable unwrap(Throwable error) {
        if ((error instanceof CompletionException || error instanceof ExecutionException)
                && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private void nak(VcmpSession session, String messageId, ProblemDetail problemDetail) {
        if (session.isOpen()) {
            if (log.isTraceEnabled()) {
                log.trace("Sending NAK for message {} with error {}", messageId, problemDetail);
            }
            try {
                val payload = problemDetail != null ? objectMapper.writeValueAsString(problemDetail) : null;
                session.sendFrame(VcmpFrame.createNak(messageId, payload));
            }
            catch (IOException e) {
                log.error("Error sending NAK", e);
            }
        }
    }

    private void ack(VcmpSession session, String messageId, Object result) {
        if (session.isOpen()) {
            if (log.isTraceEnabled()) {
                log.trace("Sending ACK for message: {}", messageId);
            }
            try {
                val payload = result != null ? objectMapper.writeValueAsString(result) : null;
                session.sendFrame(VcmpFrame.createAck(messageId, payload));
            }
            catch (IOException e) {
                log.error("Error sending ACK", e);
            }
        }
    }

    private Object invokeListener(VcmpSession session, VcmpMessage message) throws InvocationTargetException, IllegalAccessException {
        // Existence is checked before the first attempt in handleMessagePayload.
        Method listener = this.listeners.get(message.getClass()).method();
        Parameter[] parameters = listener.getParameters();
        Object[] args = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Class<?> type = parameters[i].getType();
            if (type.isAssignableFrom(message.getClass())) {
                args[i] = message;
            }
            else if (type.isAssignableFrom(VcmpSession.class)) {
                args[i] = session;
            }
            else if (type.isAssignableFrom(String.class)) {
                args[i] = session.getUsername();
            }
        }
        return listener.invoke(target, args);
    }


    @Override
    public boolean supportsPartialMessages() {
        return true;
    }

    private static HashMap<Class<?>, Listener> findListeners(Object target) {
        Class<?> targetClass = ClassUtils.getTargetClass(target);
        log.info("Detecting listeners on {}", targetClass.getSimpleName());
        HashMap<Class<?>, Listener> listeners = new HashMap<>();
        for (Method method : targetClass.getMethods()) {
            VcmpListener annotation = method.getAnnotation(VcmpListener.class);
            if (annotation != null) {
                Optional<Class<?>> messageType = Stream.of(method.getParameters())
                        .filter(parameter -> VcmpMessage.class.isAssignableFrom(parameter.getType()))
                        .findFirst()
                        .map(Parameter::getType);

                if (messageType.isPresent()) {
                    log.info(" - `{}` handled by `{}`{}", messageType.get().getSimpleName(), method.getName(),
                            annotation.retry() ? " (with retry)" : "");
                    listeners.put(messageType.get(), new Listener(method, annotation.retry()));
                }
                else {
                    log.error("Could not detect message type on @VcmpListener {}#{}", targetClass.getSimpleName(), method.getName());
                }
            }
        }
        return listeners;
    }

    private VcmpSession getSession(WebSocketSession webSocketSession) {
        return this.sessions.get(webSocketSession.getId());
    }

    String serializeMessage(VcmpMessage message) throws JsonProcessingException {
        return objectMapper.writeValueAsString(message);
    }

    static ProblemDetail createProblemDetail(Throwable throwable) {
        if (throwable instanceof ErrorResponseException errorResponseException) {
            return errorResponseException.getBody();
        }
        val problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, throwable.getMessage());
        problemDetail.setTitle("Message handling failed");
        return problemDetail;
    }

    ProblemDetail parseProblemDetail(String payload) {
        if (!hasText(payload)) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Message handling failed.");
        }
        try {
            return objectMapper.readValue(payload, ProblemDetail.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse ProblemDetail from payload: {}", payload, e);
            return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to parse ProblemDetail");
        }
    }

}
