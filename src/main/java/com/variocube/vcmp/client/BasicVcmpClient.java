package com.variocube.vcmp.client;

import com.variocube.vcmp.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

@Slf4j
public class BasicVcmpClient {

    @Getter(AccessLevel.PROTECTED)
    private VcmpSession session;

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
            return VcmpCallback.failed(LocalProblem.mark(problemDetail));
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
