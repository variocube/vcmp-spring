package com.variocube.vcmp.pendingack;

import com.variocube.vcmp.VcmpCallback;
import com.variocube.vcmp.VcmpListener;
import com.variocube.vcmp.client.BasicVcmpClient;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A client whose listener never completes the callback it returns, so it never ACKs
 * a {@link PendingAckMessage} it receives.
 */
public class PendingAckClient extends BasicVcmpClient {

    private final AtomicBoolean received = new AtomicBoolean(false);

    @VcmpListener
    public VcmpCallback<Void> handlePendingAckMessage(PendingAckMessage message) {
        received.set(true);
        return new VcmpCallback<>();
    }

    boolean hasReceivedMessage() {
        return received.get();
    }

    void closeSession() throws IOException {
        getSession().close();
    }

}
