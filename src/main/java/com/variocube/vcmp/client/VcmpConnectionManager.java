package com.variocube.vcmp.client;

import com.variocube.vcmp.ClassUtils;
import com.variocube.vcmp.Executor;
import com.variocube.vcmp.MethodAnnotationUtils;
import com.variocube.vcmp.VcmpClientAuthentication;
import com.variocube.vcmp.VcmpHandler;
import jakarta.websocket.WebSocketContainer;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.tomcat.websocket.Constants;
import org.apache.tomcat.websocket.WsWebSocketContainer;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

@Slf4j
public class VcmpConnectionManager implements Closeable {

    /*
     * NOTE: The `SEND_TIMEOUT` and the `MAX_TEXT_MESSAGE_BUFFER_SIZE` specify
     * the minimum required bandwidth for a functioning connection.
     * Web socket messages are split into frames of `MAX_TEXT_MESSAGE_BUFFER_SIZE`
     * and each of these frames must be sent within `SEND_TIMEOUT`. Otherwise
     * an exception will be thrown when sending a message.
     */
    /**
     * Send timeout for a message part (web socket frame) in milliseconds.
     */
    private static final long SEND_TIMEOUT = 10_000L; // 10 seconds

    /**
     * Buffer size for text messages.
     */
    private static final int MAX_TEXT_MESSAGE_BUFFER_SIZE = 8 * 1024; // 8 KB


    /**
     * Default minimum timeout before reconnecting after the connection was closed in seconds.
     */
    static final long DEFAULT_RECONNECT_TIMEOUT_MIN_SECONDS = 10L;

    /**
     * Default minimum timeout before reconnecting after the connection was closed as a Duration.
     */
    static final Duration DEFAULT_RECONNECT_TIMEOUT_MIN = Duration.ofSeconds(DEFAULT_RECONNECT_TIMEOUT_MIN_SECONDS);


    /**
     * Default maximum timeout before reconnecting after the connection was closed in seconds.
     */
    static final long DEFAULT_RECONNECT_TIMEOUT_MAX_SECONDS = 30;

    /**
     * Default maximum timeout before reconnecting after the connection was closed as a Duration.
     */
    static final Duration DEFAULT_RECONNECT_TIMEOUT_MAX = Duration.ofSeconds(DEFAULT_RECONNECT_TIMEOUT_MAX_SECONDS);


    /**
     * Default timeout after a disconnect in seconds.
     */
    static final long DEFAULT_DISCONNECT_TIMEOUT_SECONDS = 10;

    /**
     * Default timeout after a disconnect as a `Duration`.
     */
    static final Duration DEFAULT_DISCONNECT_TIMEOUT = Duration.ofSeconds(DEFAULT_DISCONNECT_TIMEOUT_SECONDS);

    /**
     * The maximum session idle timeout in milliseconds.
     */
    private static final long MAX_SESSION_IDLE_TIMEOUT = 60_000; // 60 seconds

    private static final Random RANDOM = new Random();

    private final StandardWebSocketClient webSocketClient;
    private final Object target;
    private final Object lifecycleMonitor = new Object();

    private final URI uri;
    private final VcmpHandler vcmpHandler;

    @Getter
    @Setter
    private Duration reconnectTimeoutMin = DEFAULT_RECONNECT_TIMEOUT_MIN;

    @Getter
    @Setter
    private Duration reconnectTimeoutMax = DEFAULT_RECONNECT_TIMEOUT_MAX;

    @Getter
    @Setter
    private Duration disconnectTimeout = DEFAULT_DISCONNECT_TIMEOUT;

    /**
     * With a {@link VcmpCredential}: how long an authentication attempt may take, from requesting the credential to the
     * server's answer to the {@code AUT}.
     */
    @Getter
    private Duration authTimeout = VcmpClientAuthentication.DEFAULT_ANSWER_TIMEOUT;

    /**
     * @throws IllegalArgumentException below 1 s.
     */
    public void setAuthTimeout(Duration authTimeout) {
        VcmpClientAuthentication.requireValidAnswerTimeout(authTimeout);
        this.authTimeout = authTimeout;
    }

    /**
     * Re-authenticates the current session: asks the {@link VcmpCredential} method for a fresh credential and sends it
     * in one {@code AUT} on the open session. The client schedules no re-authentication itself: call this when the
     * credential has been refreshed (e.g. from a token-refreshed callback), before the session's authentication
     * expires ({@code VcmpSession.getAuthenticationExpiresAt()}); otherwise the server closes the session at the
     * expiry and the client reconnects with a fresh credential.
     * <p>
     * The future completes
     * <ul>
     *     <li>with the members of the server's {@code ACK} payload (incl. {@code expiresIn}; empty if it has
     *     none);</li>
     *     <li>exceptionally with an {@code ErrorResponseException} carrying the problem, which is also reported to the
     *     {@link VcmpAuthenticationFailed} methods: a {@code NAK} (the server's status) or a credential method that
     *     failed (500) or did not complete within the answer timeout (504) keeps the session (nothing is retried; call
     *     again if you like); an {@code AUT} without an answer within the answer timeout (504) closes the session
     *     (4008), which reconnects; the session closing while the {@code AUT} is pending is a 503;</li>
     *     <li>with {@code null} if nothing was sent because there is no authenticated session (not started,
     *     connecting, disconnected): the next connect asks the credential method anyway.</li>
     * </ul>
     * Only one {@code AUT} is in flight at a time: calls while one is pending (the first {@code AUT} or a
     * re-authentication) are served together by one further {@code AUT} after its answer, with a fresh credential.
     *
     * @throws IllegalStateException (in the future) if the client has no {@link VcmpCredential} method.
     */
    public CompletableFuture<Map<String, Object>> reauthenticate() {
        if (credentialMethod == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("The client has no @VcmpCredential method."));
        }
        return vcmpHandler.reauthenticate();
    }

    /** The target's {@link VcmpCredential} method, or {@code null} if the client does not authenticate with AUT. */
    private final Method credentialMethod;

    private final List<Method> authenticationFailedMethods = new ArrayList<>();


    private WebSocketSession webSocketSession;

    /**
     * The last error connecting, incl. a failed session authentication (an {@code ErrorResponseException} with the
     * problem); {@code null} once connected (with a {@link VcmpCredential}: once (re)authenticated).
     */
    @Getter
    private volatile Throwable connectionError;

    private boolean isRunning = false;


    public VcmpConnectionManager(Object target, String uriTemplate, Object... uriVariables) {
        this.target = target;
        this.credentialMethod = findCredentialMethod(target);
        registerOn(target);
        for (Method method : ClassUtils.getTargetClass(target).getMethods()) {
            if (method.isAnnotationPresent(VcmpAuthenticationFailed.class)) {
                if (method.getParameterCount() > 1 || (method.getParameterCount() == 1
                        && !method.getParameterTypes()[0].isAssignableFrom(ProblemDetail.class))) {
                    throw new IllegalStateException("@VcmpAuthenticationFailed " + method.getName()
                            + " may only take a ProblemDetail.");
                }
                authenticationFailedMethods.add(method);
            }
        }
        this.uri = UriComponentsBuilder.fromUriString(uriTemplate).buildAndExpand(uriVariables).encode().toUri();
        this.vcmpHandler = new VcmpHandler(target);
        this.vcmpHandler.setDisconnectHandler(this::handleDisconnect);

        WebSocketContainer webSocketContainer = new WsWebSocketContainer();
        webSocketContainer.setDefaultMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_BUFFER_SIZE);
        webSocketContainer.setDefaultMaxSessionIdleTimeout(MAX_SESSION_IDLE_TIMEOUT);
        this.webSocketClient  = new StandardWebSocketClient(webSocketContainer);
        this.webSocketClient.setUserProperties(Collections.singletonMap(Constants.BLOCKING_SEND_TIMEOUT_PROPERTY, SEND_TIMEOUT));

    }

    private WebSocketHttpHeaders createHeaders() throws InvocationTargetException, IllegalAccessException {
        val headers = new WebSocketHttpHeaders();
        headers.put(WebSocketHttpHeaders.SEC_WEBSOCKET_EXTENSIONS, Collections.singletonList("permessage-deflate"));
        MethodAnnotationUtils.invokeMethodWithAnnotation(target, VcmpHttpHeaders.class, headers);
        return headers;
    }

    /**
     * Lets a {@link BasicVcmpClient} target reach this manager for {@link BasicVcmpClient#reauthenticate()}. A Spring
     * bean may be an AOP proxy (e.g. for {@code @Async}, {@code @Transactional}): the proxy runs
     * {@code reauthenticate()} on its target, so the manager is set on the target too.
     */
    private void registerOn(Object target) {
        if (target instanceof BasicVcmpClient basicVcmpClient) {
            basicVcmpClient.setConnectionManager(this);
        }
        if (AopUtils.isAopProxy(target)) {
            val unwrapped = AopProxyUtils.getSingletonTarget(target);
            if (unwrapped != null && unwrapped != target) {
                registerOn(unwrapped);
            }
        }
    }

    private static Method findCredentialMethod(Object target) {
        Method found = null;
        for (Method method : ClassUtils.getTargetClass(target).getMethods()) {
            if (method.isAnnotationPresent(VcmpCredential.class)) {
                if (found != null) {
                    throw new IllegalStateException("More than one @VcmpCredential method.");
                }
                if (method.getParameterCount() != 0 || method.getReturnType() == void.class) {
                    throw new IllegalStateException("@VcmpCredential " + method.getName()
                            + " must take no parameters and return the credential.");
                }
                found = method;
            }
        }
        return found;
    }

    private CompletionStage<?> supplyCredential() {
        try {
            val credential = credentialMethod.invoke(target);
            return credential instanceof CompletionStage<?> stage ? stage : CompletableFuture.completedFuture(credential);
        }
        catch (InvocationTargetException e) {
            return CompletableFuture.failedFuture(e.getCause());
        }
        catch (IllegalAccessException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private void authenticationFailed(ProblemDetail problemDetail) {
        val status = problemDetail.getStatus() >= 100 && problemDetail.getStatus() <= 999
                ? HttpStatusCode.valueOf(problemDetail.getStatus())
                : HttpStatusCode.valueOf(500);
        connectionError = new ErrorResponseException(status, problemDetail, null);
        for (Method method : authenticationFailedMethods) {
            try {
                if (method.getParameterCount() == 1) {
                    method.invoke(target, problemDetail);
                }
                else {
                    method.invoke(target);
                }
            }
            catch (InvocationTargetException | IllegalAccessException | RuntimeException e) {
                log.error("Error in @VcmpAuthenticationFailed method {}", method.getName(), e);
            }
        }
    }

    public void start() {
        log.info("Starting connection to {}", this.uri);
        if (credentialMethod != null) {
            vcmpHandler.setClientAuthentication(new VcmpClientAuthentication(this::supplyCredential, authTimeout,
                    this::authenticationFailed, () -> connectionError = null));
        }
        synchronized (this.lifecycleMonitor) {
            this.isRunning = true;
            openSession();
        }
    }

    public void stop() throws IOException {
        log.info("Stopping connection to {}", this.uri);
        synchronized (this.lifecycleMonitor) {
            this.isRunning = false;
            closeSession();
        }
    }

    private void handleDisconnect() {
        scheduleReconnect(true);
    }

    private void scheduleReconnect(boolean afterDisconnect) {
        // isRunning and webSocketSession are guarded by lifecycleMonitor; read/write them under the
        // same lock that start()/stop() use, so a reconnect triggered from a disconnect/executor
        // thread can never observe a stale isRunning after stop() (JMM visibility) and never races
        // a concurrent stop() between the check and scheduling.
        synchronized (this.lifecycleMonitor) {
            webSocketSession = null;
            if (this.isRunning) {
                val baseDelay = afterDisconnect ? disconnectTimeout : Duration.ZERO;
                val randomDelay = Duration.ofMillis(RANDOM.nextLong(reconnectTimeoutMin.toMillis(), reconnectTimeoutMax.toMillis() + 1));
                val reconnectTimeoutMs = baseDelay.plus(randomDelay).toMillis();
                log.info("Scheduling reconnect in {} ms", reconnectTimeoutMs);
                try {
                    Executor.getExecutor().schedule(this::openSession, reconnectTimeoutMs, TimeUnit.MILLISECONDS);
                }
                catch (Exception e) {
                    log.error("Could not schedule reconnect", e);
                }
            }
        }
    }

    private void openSession() {
        // Hold lifecycleMonitor across the isRunning check and the handshake kick-off so stop()
        // cannot land between them (check-then-act). webSocketClient.execute() submits the handshake
        // to an async executor and returns immediately, so the lock is not held across blocking I/O.
        synchronized (this.lifecycleMonitor) {
            if (this.isRunning) {
                log.info("Initiate handshake with {}", this.uri);
                // Create a separate header snapshot for every attempt, including retries. Never reuse
                // a previously minted token or mutate headers retained by an in-flight handshake.
                try {
                    val headers = createHeaders();
                    webSocketClient.execute(this.vcmpHandler, headers, this.uri)
                        .thenAccept(session -> {
                            // The handshake completes asynchronously, so stop() may already have run by
                            // the time we get here. Decide what to do under the same lock stop() uses:
                            // if we are no longer running, closeSession() has already executed (and found
                            // webSocketSession still null, closing nothing), so we must close this
                            // freshly-established session ourselves — otherwise it leaks and stays open
                            // until the JVM exits, stranding the peer as "connected".
                            synchronized (this.lifecycleMonitor) {
                                if (!this.isRunning) {
                                    log.info("Connection established after stop(); closing it to avoid leaking the session.");
                                    closeQuietly(session);
                                    return;
                                }
                                log.info("Connection established.");
                                webSocketSession = session;
                                connectionError = null;
                            }
                        })
                        .exceptionally(ex -> {
                            connectionFailed(ex);
                            return null;
                        });
                }
                catch (InvocationTargetException | IllegalAccessException | RuntimeException ex) {
                    // A credential provider failure must not send stale/anonymous credentials or
                    // prevent future reconnect attempts (for example after its dependency recovers).
                    connectionFailed(ex);
                }
            }
        }
    }

    private void connectionFailed(Throwable failure) {
        if (connectionError == null) {
            log.error("Failed to connect", failure);
        }
        else {
            log.warn("Failed to connect");
        }
        connectionError = failure;
        scheduleReconnect(false);
    }

    private void closeSession() throws IOException {
        if (this.webSocketSession != null) {
            this.webSocketSession.close();
        }
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        }
        catch (IOException e) {
            log.warn("Failed to close session established after stop()", e);
        }
    }

    @Override
    public void close() throws IOException {
        this.stop();
    }
}
