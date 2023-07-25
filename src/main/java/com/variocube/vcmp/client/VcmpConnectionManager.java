package com.variocube.vcmp.client;

import com.variocube.vcmp.Executor;
import com.variocube.vcmp.MethodAnnotationUtils;
import com.variocube.vcmp.VcmpHandler;
import jakarta.websocket.WebSocketContainer;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.tomcat.websocket.Constants;
import org.apache.tomcat.websocket.WsWebSocketContainer;
import org.springframework.lang.Nullable;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.time.Duration;
import java.util.Collections;
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
     * Send timeout for a message part (web socket frame) in seconds.
     */
    private static final long SEND_TIMEOUT = 10 * 1000; // 10 seconds

    /**
     * Buffer size for text messages.
     */
    private static final int MAX_TEXT_MESSAGE_BUFFER_SIZE = 8 * 1024; // 8 KB


    /**
     * Default minimum timeout before reconnecting after the connection was closed in seconds.
     */
    static final long DEFAULT_RECONNECT_TIMEOUT_MIN_SECONDS = 10;

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
     * Default timeout after a disconnect as a Duration.
     */
    static final Duration DEFAULT_DISCONNECT_TIMEOUT = Duration.ofSeconds(DEFAULT_DISCONNECT_TIMEOUT_SECONDS);

    private static final long MAX_SESSION_IDLE_TIMEOUT = 60 * 1000; // 60 seconds

    private final StandardWebSocketClient webSocketClient;
    private final WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
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


    private WebSocketSession webSocketSession;

    @Getter
    private Throwable connectionError;

    private boolean isRunning = false;


    public VcmpConnectionManager(Object target, String uriTemplate, Object... uriVariables) {
        this.uri = UriComponentsBuilder.fromUriString(uriTemplate).buildAndExpand(uriVariables).encode().toUri();
        this.vcmpHandler = new VcmpHandler(target);
        this.vcmpHandler.setDisconnectHandler(this::handleDisconnect);

        this.headers.put(WebSocketHttpHeaders.SEC_WEBSOCKET_EXTENSIONS, Collections.singletonList("permessage-deflate"));

        WebSocketContainer webSocketContainer = new WsWebSocketContainer();
        webSocketContainer.setDefaultMaxTextMessageBufferSize(MAX_TEXT_MESSAGE_BUFFER_SIZE);
        webSocketContainer.setDefaultMaxSessionIdleTimeout(MAX_SESSION_IDLE_TIMEOUT);
        this.webSocketClient  = new StandardWebSocketClient(webSocketContainer);
        this.webSocketClient.setUserProperties(Collections.singletonMap(Constants.BLOCKING_SEND_TIMEOUT_PROPERTY, SEND_TIMEOUT));

        try {
            MethodAnnotationUtils.invokeMethodWithAnnotation(target, VcmpHttpHeaders.class, headers);
        } catch (InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException("Error invoking @VcmpHttpHeaders method.", e);
        }
    }

    public void start() {
        log.info("Starting connection to {}", this.uri);
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
        webSocketSession = null;
        if (this.isRunning) {
            val baseDelay = afterDisconnect ? disconnectTimeout : Duration.ZERO;
            val randomDelay = Duration.ofMillis((long) (Math.random() * reconnectTimeoutMax.minus(reconnectTimeoutMin).toMillis()));
            val reconnectTimeoutMs = baseDelay.plus(reconnectTimeoutMin).plus(randomDelay).toMillis();
            log.info("Scheduling reconnect in {} ms", reconnectTimeoutMs);
            try {
                Executor.getExecutor().schedule(this::openSession, reconnectTimeoutMs, TimeUnit.MILLISECONDS);
            }
            catch (Exception e) {
                log.error("Could not schedule reconnect", e);
            }
        }
    }

    private void openSession() {
        if (this.isRunning) {
            log.info("Initiate handshake with {}", this.uri);
            // shake them hands...
            webSocketClient.doHandshake(this.vcmpHandler, this.headers, this.uri)
                    .addCallback(new ListenableFutureCallback<>() {
                        @Override
                        public void onSuccess(@Nullable WebSocketSession result) {
                            log.info("Connection established.");
                            webSocketSession = result;
                            connectionError = null;
                        }

                        @Override
                        public void onFailure(Throwable ex) {
                            // Repeated connect failures are logged as warnings without a stack trace.
                            if (connectionError == null) {
                                log.error("Failed to connect", ex);
                            }
                            else {
                                log.warn("Failed to connect");
                            }
                            connectionError = ex;
                            scheduleReconnect(false);
                        }
                    });
        }
    }

    private void closeSession() throws IOException {
        if (this.webSocketSession != null) {
            this.webSocketSession.close();
        }
    }

    @Override
    public void close() throws IOException {
        this.stop();
    }
}
