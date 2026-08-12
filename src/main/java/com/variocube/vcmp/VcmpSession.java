package com.variocube.vcmp;

import jakarta.websocket.CloseReason;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static com.variocube.vcmp.VcmpHandler.createProblemDetail;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@Slf4j
public class VcmpSession {

    /**
     * The timeout to wait for acquiring the `send`-lock in seconds.
     * <p>
     * This needs to account for sending big messages on slow networks.
     * However, it should be small enough that we see frames rejected because
     * another sender holds the lock too long.
     */
    private static final int SEND_LOCK_TIMEOUT = 30;

    private final WebSocketSession webSocketSession;
    private final VcmpHandler vcmpHandler;

    private final ConcurrentHashMap<String, VcmpCallback<?>> callbacks = new ConcurrentHashMap<>();
    private final ReentrantLock sendLock = new ReentrantLock();

    private Instant lastHeartbeatReceived;

    // Whether we await a heartbeat. Must be initially true to allow the other party to initiate a heartbeat.
    private final AtomicBoolean awaitingHeartbeat = new AtomicBoolean(true);

    @Getter
    private int heartbeatReceivedCount = 0;

    @Getter(AccessLevel.PACKAGE)
    private final MessageBuffer messageBuffer = new MessageBuffer();

    public VcmpCallback<Void> send(VcmpMessage message) {
        return send(message, Void.class);
    }

    public <T> VcmpCallback<T> send(VcmpMessage message, Class<T> resultClass) {
        VcmpFrame vcmpFrame;
        try {
            vcmpFrame = VcmpFrame.createMessage(vcmpHandler.serializeMessage(message));
        }
        catch (IOException e) {
            return VcmpCallback.failed(createProblemDetail(e));
        }

        VcmpCallback<T> callback = new VcmpCallback<>(resultClass);
        callbacks.put(vcmpFrame.getId(), callback);
        try {
            sendFrame(vcmpFrame);
        }
        catch (SessionClosedException e) {
            callbacks.remove(vcmpFrame.getId());
            return VcmpCallback.failed(sessionClosedProblemDetail(e.getMessage()));
        }
        catch (IOException e) {
            callbacks.remove(vcmpFrame.getId());
            return VcmpCallback.failed(createProblemDetail(e));
        }
        catch (RuntimeException e) {
            // e.g. an IllegalStateException from the container when the peer disconnects
            // between the isOpen() check and reading the session's message size limit.
            // Without this catch, the callbacks entry would be stranded forever and the
            // exception would unwind a caller iterating over sessions (broadcast).
            callbacks.remove(vcmpFrame.getId());
            log.warn("Unexpected error while sending frame", e);
            if (!isOpen()) {
                return VcmpCallback.failed(sessionClosedProblemDetail(
                        "The session was closed while sending the message."));
            }
            return VcmpCallback.failed(createProblemDetail(e));
        }
        return callback;
    }

    /**
     * Fails all pending callbacks of this session with a NAK.
     * Called when the underlying connection closes, so that senders and chained
     * ACKs are notified instead of pending forever.
     * <p>
     * Note that this is the only mechanism that settles a pending callback without an
     * ACK/NAK frame: VCMP itself does not bound how long a peer may take to acknowledge.
     * Callers that need such a bound impose it themselves, e.g. via
     * {@link VcmpCallback#awaitSeconds(int)}.
     * <p>
     * Runs on the VCMP executor, after the container's close handling — the synchronous
     * {@code @VcmpSessionDisconnected} dispatch may already have run, so client-side state
     * (e.g. {@code BasicVcmpClient}'s session reference) may already be cleared when a NAK
     * handler fires; a handler that reacts by re-sending fails fast instead of blocking.
     * NAK handlers are delivered serially here, so under a large fan-out a later callback
     * may observe its NAK only after the earlier handlers (including chained NAK frames on
     * other sessions) have completed.
     */
    void failPendingCallbacks() {
        for (val id : callbacks.keySet()) {
            // One ProblemDetail per callback: it is mutable, and a NAK handler that annotates
            // it must not leak those changes into the NAKs delivered to other callbacks.
            val problemDetail = sessionClosedProblemDetail(
                    "The session was closed before the message was acknowledged.");
            try {
                notifyNak(id, problemDetail);
            }
            catch (Exception e) {
                // A throwing NAK handler (consumer code) must not abort the drain — the
                // remaining callbacks would silently stay pending forever otherwise.
                log.error("Error failing pending callback {}", id, e);
            }
        }
    }

    /**
     * Creates the {@code 503 Session closed} problem detail used for every callback that fails
     * because this session is gone. Matches the status the JS implementation reports for the same
     * conditions (variocube/vcmp-js#32), so consumers can treat 503 uniformly as a retryable
     * transport condition.
     */
    private static ProblemDetail sessionClosedProblemDetail(String detail) {
        val problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, detail);
        problemDetail.setTitle("Session closed");
        return problemDetail;
    }

    public void initiateHeartbeat(int intervalMillis) {
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
            Executor.getExecutor().schedule(() -> sendHeartbeat(heartbeat), heartbeat.getHeartbeatInterval(), TimeUnit.MILLISECONDS);
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
                Executor.getExecutor().schedule(() -> {
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
                }, heartbeat.getHeartbeatInterval() * 2L, TimeUnit.MILLISECONDS);
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

    /**
     * Sends a VCMP frame on the underlying WebSocketSession.
     * This method uses a lock to avoid sending on multiple
     * threads concurrently.
     * @param frame The frame to send
     * @throws IOException If an error occurred while sending
     */
    void sendFrame(VcmpFrame frame) throws IOException {
        if (this.webSocketSession.isOpen()) {
            log.trace("Sending frame {}", frame);

            val it = new StringChunkIterator(frame.serialize(), webSocketSession.getTextMessageSizeLimit());

            // Acquire lock
            try {
                if (!sendLock.tryLock(SEND_LOCK_TIMEOUT, TimeUnit.SECONDS)) {
                    throw new IOException("Could not acquire send lock.");
                }
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Thread was interrupted while acquiring lock.");
            }

            // Send chunks
            try {
                log.trace("Acquired send lock. Start sending chunks...");
                while (it.hasNext()) {
                    val message = new TextMessage(it.next(), !it.hasNext());
                    log.trace("Sending chunk {}", message);
                    this.webSocketSession.sendMessage(message);
                }
            }
            catch (Exception e) {
                log.warn("Error while sending web socket message", e);
                this.webSocketSession.close(new CloseStatus(CloseReason.CloseCodes.CLOSED_ABNORMALLY.getCode(), "Error while sending message"));
                // The session is gone as a result of this failure — report it like any other
                // send on a closed session (503), not as a peer-side error (500).
                throw new SessionClosedException("The session was closed due to an error while sending the message.", e);
            }
            finally {
                // Make sure to release lock in any case
                sendLock.unlock();
            }

            log.trace("Finished sending frame {}", frame);
        }
        else {
            throw new SessionClosedException();
        }
    }

    /**
     * Signals that a frame could not be sent because the underlying session is already closed —
     * as opposed to an error while sending on an open session.
     */
    static class SessionClosedException extends IOException {
        SessionClosedException() {
            super("Cannot send message: the session is already closed.");
        }

        SessionClosedException(String message, Throwable cause) {
            super(message, cause);
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

    public Principal getPrincipal() {
        return webSocketSession.getPrincipal();
    }

    public void close() throws IOException {
        webSocketSession.close();
    }

    public boolean isOpen() {
        return webSocketSession.isOpen();
    }

    void notifyNak(String id, ProblemDetail problemDetail) {
        Optional.ofNullable(callbacks.remove(id))
                .ifPresent(callback -> callback.notifyNak(problemDetail));
    }

    void notifyAck(String id, String resultPayload) {
        Optional.ofNullable(callbacks.remove(id))
                .ifPresent(callback -> callback.notifyAckRaw(resultPayload));
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof VcmpSession otherSession) {
            return Objects.equals(this.getId(), otherSession.getId());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return this.getId().hashCode();
    }
}
