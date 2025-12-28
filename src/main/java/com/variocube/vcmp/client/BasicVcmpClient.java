package com.variocube.vcmp.client;

import com.variocube.vcmp.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
        assertSession();
        return this.session.send(message, resultClass);
    }

    public void send(VcmpMessage message, Runnable ack) {
        this.send(message, ack, null);
    }

    public void send(VcmpMessage message, Runnable ack, Runnable nak) {
        assertSession();
        val callback = this.send(message);
        callback.onAck(ack);
        callback.onNak(nak);
    }

    public boolean isConnected() {
        return this.session != null;
    }

    private void assertSession() {
        if (session == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Not connected.");
        }
    }

}
