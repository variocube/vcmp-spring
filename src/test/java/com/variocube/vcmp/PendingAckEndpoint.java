package com.variocube.vcmp;

import com.variocube.vcmp.server.VcmpEndpoint;

/**
 * An endpoint that never completes the callback returned from its listener,
 * so the ACK for the incoming message is never sent.
 */
@VcmpEndpoint(path = "/pendingack")
public class PendingAckEndpoint {

    @VcmpListener
    public VcmpCallback<Void> handlePendingAckMessage(PendingAckMessage message) {
        return new VcmpCallback<>();
    }

}
