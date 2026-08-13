package com.variocube.vcmp.pendingack;

import com.variocube.vcmp.VcmpCallback;
import com.variocube.vcmp.VcmpListener;
import com.variocube.vcmp.VcmpSession;
import com.variocube.vcmp.VcmpTestEndpoint;
import com.variocube.vcmp.server.VcmpEndpoint;
import lombok.val;

/**
 * Forwards an incoming {@link PendingAckMessage} to every other connected session and returns the
 * chained callback, so the ACK for the incoming message is deferred until the downstream sessions
 * acknowledge. With no other session connected, it returns a callback that is never completed at
 * all — either way the sender is not ACKed until something else settles the callback.
 */
@VcmpEndpoint(path = "/pendingack")
public class PendingAckEndpoint extends VcmpTestEndpoint {

    @VcmpListener
    public VcmpCallback<Void> handlePendingAckMessage(PendingAckMessage message, VcmpSession sender) {
        val forwards = getSessionPool().getOpenSessions().stream()
                .filter(session -> !session.equals(sender))
                .map(session -> session.send(message))
                .toList();
        if (forwards.isEmpty()) {
            return new VcmpCallback<>();
        }
        return VcmpCallback.all(forwards).map(results -> null);
    }

}
