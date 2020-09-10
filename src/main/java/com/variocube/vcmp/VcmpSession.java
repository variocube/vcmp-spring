package com.variocube.vcmp;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Slf4j
public class VcmpSession {
    private final WebSocketSession webSocketSession;
    private final VcmpHandler vcmpHandler;
    private final TaskScheduler taskScheduler;

    private final ConcurrentHashMap<String, VcmpCallback> callbacks = new ConcurrentHashMap<>();

    private Instant lastHeartbeatReceived;

    // Whether we await a heartbeat. Must be initially true to allow the other party to initiate a heartbeat.
    private final AtomicBoolean awaitingHeartbeat = new AtomicBoolean(true);

    @Getter
    private int heartbeatReceivedCount = 0;

    @Getter(AccessLevel.PACKAGE)
    private final MessageBuffer messageBuffer = new MessageBuffer();

    public VcmpCallback send(VcmpMessage message) throws IOException {
        VcmpFrame vcmpFrame = VcmpFrame.createMessage(vcmpHandler.serializeMessage(message));
        VcmpCallback callback = new VcmpCallback();
        callbacks.put(vcmpFrame.getId(), callback);
        sendFrame(vcmpFrame);
        return callback;
    }

    public void send(VcmpMessage message, Runnable ack, Runnable nak) throws IOException {
        VcmpFrame vcmpFrame = VcmpFrame.createMessage(vcmpHandler.serializeMessage(message));
        if (ack != null || nak != null) {
            VcmpCallback callback = new VcmpCallback();
            if (ack != null) {
                callback.onAck(ack);
            }
            if (nak != null) {
                callback.onNak(nak);
            }
            callbacks.put(vcmpFrame.getId(), callback);
        }
        sendFrame(vcmpFrame);
    }

    public void initiateHeartbeat(int intervalMillis) throws IOException {
        sendHeartbeat(VcmpFrame.createHeartbeat(intervalMillis));
    }

    void handleHeartbeatReceived(VcmpFrame heartbeat) {
        log.debug("Received heartbeat in session {} of {}ms", getId(), heartbeat.getHeartbeatInterval());

        // make sure to process only one heartbeat during a waiting window
        // if we await a heartbeat, process it and atomically set the flag to false,
        // so further heartbeats will be ignored
        if (this.awaitingHeartbeat.getAndSet(false)) {
            this.lastHeartbeatReceived = Instant.now();
            this.heartbeatReceivedCount++;
            taskScheduler.schedule(() -> sendHeartbeat(heartbeat), Instant.now().plusMillis(heartbeat.getHeartbeatInterval()));
        }
    }

    private void sendHeartbeat(VcmpFrame heartbeat) {
        try {
            if (this.isOpen()) {
                log.debug("Sending heartbeat in session {} of {}ms", getId(), heartbeat.getHeartbeatInterval());

                // send the frame
                this.sendFrame(heartbeat);

                // set the flag that we await a heartbeat
                this.awaitingHeartbeat.set(true);

                // remember when we sent the heartbeat
                Instant sent = Instant.now();

                // verify we received a heartbeat back, after double the interval
                // if everything works fine we should receive the heartbeat after (interval + latency)
                taskScheduler.schedule(() -> {
                    if (this.isOpen()) {
                        if (this.lastHeartbeatReceived == null || this.lastHeartbeatReceived.isBefore(sent)) {
                            log.error("Did not receive heartbeat in time. Closing session.");
                            try {
                                // This can actually lead to the underlying socket left open, which can in turn
                                // lead to having too many open files. Anyway, we want to avoid an uncertain state.
                                this.close();
                            }
                            catch (IOException e) {
                                log.error("Error closing session", e);
                            }
                        }
                    }
                }, Instant.now().plusMillis(heartbeat.getHeartbeatInterval() * 2));
            }
        }
        catch (IOException e) {
            log.error("Could not send heartbeat", e);
            try {
                if (this.isOpen()) {
                    this.close();
                }
            }
            catch (IOException ex) {
                log.error("Could not send heartbeat", ex);
            }
        }
    }

    void sendFrame(VcmpFrame frame) throws IOException {
        sendMessage(new TextMessage(frame.serialize()));
    }

    /**
     * Sends a websocket message on the underlying WebSocketSession.
     * This method must be synchronized to avoid sending on multiple
     * threads concurrently.
     * @param message The message to send
     * @throws IOException If an error occurred while sending
     */
    @Synchronized
    private void sendMessage(WebSocketMessage<?> message) throws IOException {
        if (this.webSocketSession.isOpen()) {
            if (log.isTraceEnabled()) {
                log.trace("Sending message {}", message);
            }
            this.webSocketSession.sendMessage(message);
        }
        else {
            throw new IOException("Session already closed.");
        }
    }


    public String getUsername() {
        return getPrincipalName()
                .orElse(null);
    }

    public boolean hasUsername(String userName) {
        return getPrincipalName()
                .filter(name -> name.equals(userName))
                .isPresent();
    }

    private Optional<String> getPrincipalName() {
        return Optional.ofNullable(webSocketSession.getPrincipal())
                .map(Principal::getName);
    }

    public String getId() {
        return webSocketSession.getId();
    }

    public URI getUri() {
        return webSocketSession.getUri();
    }

    public void close() throws IOException {
        webSocketSession.close();
    }

    public boolean isOpen() {
        return webSocketSession.isOpen();
    }

    void notifyNak(String id) {
        Optional.ofNullable(callbacks.remove(id))
                .ifPresent(VcmpCallback::notifyNak);
    }

    void notifyAck(String id) {
        Optional.ofNullable(callbacks.remove(id))
                .ifPresent(VcmpCallback::notifyAck);
    }

    @Override
    public boolean equals(Object obj) {
        return Optional.ofNullable(obj)
                .map(VcmpSession.class::isInstance)
                .map(VcmpSession.class::cast)
                .map(VcmpSession::getId)
                .filter(id -> Objects.equals(this.getId(), id))
                .isPresent();
    }

    @Override
    public int hashCode() {
        return this.getId().hashCode();
    }

}
