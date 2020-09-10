package com.variocube.vcmp.client;

import com.variocube.vcmp.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@Slf4j
public class BasicVcmpClient {

    @Getter(AccessLevel.PROTECTED)
    private VcmpSession session;

    private final Object sessionLock = new Object();

    @VcmpSessionDisconnected
    public void handleSessionDisconnected(VcmpSession session) {
        synchronized (sessionLock) {
            this.session = null;
        }
    }

    @VcmpSessionConnected
    public void handleSessionConnected(VcmpSession session) {
        synchronized (sessionLock) {
            this.session = session;
        }
    }

    public VcmpCallback send(VcmpMessage message) throws IOException {
        synchronized (sessionLock) {
            if (session == null) {
                throw new IOException("Not connected.");
            }
            return this.session.send(message);
        }
    }

    public void send(VcmpMessage message, Runnable ack) throws IOException {
        this.send(message, ack, null);
    }

    public void send(VcmpMessage message, Runnable ack, Runnable nak) throws IOException {
        synchronized (sessionLock) {
            if (session == null) {
                throw new IOException("Not connected.");
            }
            this.session.send(message, ack, nak);
        }
    }

    public boolean isConnected() {
        return this.session != null;
    }

}
