package com.variocube.vcmp.client;

import com.variocube.vcmp.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class BasicVcmpClient {

    @Getter(AccessLevel.PROTECTED)
    private VcmpSession session;

    /** The manager connecting this client; set by the {@link VcmpConnectionManager} it is given to. */
    private volatile VcmpConnectionManager connectionManager;

    void setConnectionManager(VcmpConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    /**
     * Re-authenticates the session of a client with a {@link VcmpCredential} method (session authentication,
     * {@code AUT}); see {@link VcmpConnectionManager#reauthenticate()}. Call it when the credential has been refreshed,
     * e.g. from a token-refreshed callback: the client schedules no re-authentication itself.
     */
    public CompletableFuture<Map<String, Object>> reauthenticate() {
        val manager = this.connectionManager;
        if (manager == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("The client is not connected by a VcmpConnectionManager."));
        }
        return manager.reauthenticate();
    }

    @VcmpSessionDisconnected
    public void handleSessionDisconnected(VcmpSession session) {
        this.session = null;
    }

    @VcmpSessionConnected
    public void handleSessionConnected(VcmpSession session) {
        this.session = session;
    }

    public VcmpCallback<Void> send(VcmpMessage message) {
        return send(message, Void.class);
    }

    public <T> VcmpCallback<T> send(VcmpMessage message, Class<T> resultClass) {
        // Capture the field: the disconnect handler may null it concurrently.
        val session = this.session;
        if (session == null) {
            // Fail the callback instead of throwing: not being connected is the most common
            // transport failure, and must surface through the same NAK path (retryable 503)
            // as every other one — see the callback-semantics section of the README.
            val problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                    "Cannot send message: the client has no session.");
            problemDetail.setTitle("Not connected");
            return VcmpCallback.failed(LocalConnectionProblem.mark(problemDetail));
        }
        return session.send(message, resultClass);
    }

    public void send(VcmpMessage message, Runnable ack) {
        this.send(message, ack, null);
    }

    public void send(VcmpMessage message, Runnable ack, Runnable nak) {
        var callback = this.send(message).peekAck(ack);
        if (nak != null) {
            callback.peekNak(nak);
        }
    }

    public boolean isConnected() {
        return this.session != null;
    }

}
