package com.variocube.vcmp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Slf4j
public final class VcmpHandler implements WebSocketHandler {

    private final ObjectMapper objectMapper;

    private final Object target;
    private final HashMap<Class<?>, Method> listeners;

    private final ConcurrentHashMap<String, VcmpSession> sessions = new ConcurrentHashMap<>();

    @Getter
    @Setter
    private Runnable disconnectHandler;

    public VcmpHandler(Object target) {

        this.target = target;

        objectMapper = createObjectMapper();

        this.listeners = findListeners(target);
        this.listeners.keySet().forEach(objectMapper::registerSubtypes);
    }

    public static ObjectMapper createObjectMapper() {
        return new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .registerModule(new JavaTimeModule());
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket session opened: {}", session.getId());

        VcmpSession vcmpSession = new VcmpSession(session, this);
        sessions.put(session.getId(), vcmpSession);

        Executor.getExecutor().submit(() -> {
            try {
                MethodAnnotationUtils.invokeMethodWithAnnotation(this.target, VcmpSessionConnected.class, vcmpSession);
            }
            catch (Throwable e) {
                log.error("Could not invoke connect handler", e);
            }
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        log.info("WebSocket session {} closed: {}", session.getId(), closeStatus);
        try {
            MethodAnnotationUtils.invokeMethodWithAnnotation(this.target, VcmpSessionDisconnected.class, sessions.remove(session.getId()));
        }
        catch (Throwable e) {
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
            if (message instanceof TextMessage) {
                handleTextMessage(vcmpSession, (TextMessage) message);
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
        log.error("Transport error in session {}: {}", session.getId(), exception.getMessage());
        log.debug("Full exception", exception);
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
                    session.notifyAck(frame.getId());
                    break;
                case NAK:
                    log.debug("Received NAK for {}.", frame.getId());
                    session.notifyNak(frame.getId());
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
        try {
            if (log.isTraceEnabled()) {
                log.trace("Handling message: {}", messageId);
            }
            VcmpMessage message = objectMapper.readValue(messagePayload, VcmpMessage.class);
            Object returnValue = invokeListener(session, message);

            VcmpCallback callback = Optional.ofNullable(returnValue)
                    .filter(VcmpCallback.class::isInstance)
                    .map(VcmpCallback.class::cast)
                    .orElse(null);

            CompletableFuture<?> completableFuture = Optional.ofNullable(returnValue)
                    .filter(CompletableFuture.class::isInstance)
                    .map(CompletableFuture.class::cast)
                    .orElse(null);

            if (callback != null) {
                callback.onAck(() -> ack(session, messageId));
                callback.onNak(() -> nak(session, messageId));
            }
            else if (completableFuture != null) {
                completableFuture.thenRun(() -> ack(session, messageId))
                        .exceptionally((error) -> {
                            log.info("Handler failed with exception. Sending NAK.", error);
                            nak(session, messageId);
                            return null;
                        });
            }
            else {
                // No callback provided. That means the listener succeeded synchronously.
                ack(session, messageId);
            }
        }
        catch (Exception ex) {
            log.error("Error invoking listener", ex);
            nak(session, messageId);
        }
    }

    private void nak(VcmpSession session, String messageId) {
        if (session.isOpen()) {
            if (log.isTraceEnabled()) {
                log.trace("Sending NAK for message: {}", messageId);
            }
            try {
                session.sendFrame(VcmpFrame.createNak(messageId));
            }
            catch (IOException e) {
                log.error("Error sending NAK", e);
            }
        }
    }

    private void ack(VcmpSession session, String messageId) {
        if (session.isOpen()) {
            if (log.isTraceEnabled()) {
                log.trace("Sending ACK for message: {}", messageId);
            }
            try {
                session.sendFrame(VcmpFrame.createAck(messageId));
            }
            catch (IOException e) {
                log.error("Error sending ACK", e);
            }
        }
    }

    private Object invokeListener(VcmpSession session, VcmpMessage message) throws Exception {
        Method listener = this.listeners.get(message.getClass());
        if (listener == null) {
            throw new Exception("Could not find listener for " + message.getClass().getSimpleName());
        }
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

    private static HashMap<Class<?>, Method> findListeners(Object target) {
        Class<?> targetClass = ClassUtils.getTargetClass(target);
        log.info("Detecting listeners on {}", targetClass.getSimpleName());
        HashMap<Class<?>, Method> listeners = new HashMap<>();
        for (Method method : targetClass.getMethods()) {
            if (method.getAnnotation(VcmpListener.class) != null) {
                Optional<Class<?>> messageType = Stream.of(method.getParameters())
                        .filter(parameter -> VcmpMessage.class.isAssignableFrom(parameter.getType()))
                        .findFirst()
                        .map(Parameter::getType);

                if (messageType.isPresent()) {
                    log.info(" - `{}` handled by `{}`", messageType.get().getSimpleName(), method.getName());
                    listeners.put(messageType.get(), method);
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

    ObjectMapper getObjectMapper() {
        return objectMapper;
    }
    ConcurrentHashMap<String, VcmpSession> getSessions() {
        return sessions;
    }

}
