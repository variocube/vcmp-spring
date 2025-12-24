package com.variocube.vcmp.client;

import com.variocube.vcmp.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.io.IOException;

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

    public VcmpCallback<Void> send(VcmpMessage message) throws IOException {
        return send(message, Void.class);
    }

    public <T> VcmpCallback<T> send(VcmpMessage message, Class<T> resultClass) throws IOException {
        if (session == null) {
            throw new IOException("Not connected.");
        }
        return this.session.send(message, resultClass);
    }

    public void send(VcmpMessage message, Runnable ack) throws IOException {
        this.send(message, ack, null);
    }

    public void send(VcmpMessage message, Runnable ack, Runnable nak) throws IOException {
        if (session == null) {
            throw new IOException("Not connected.");
        }
        val callback = this.send(message);
        callback.onAck(ack);
        callback.onNak(nak);
    }

    public boolean isConnected() {
        return this.session != null;
    }

}
