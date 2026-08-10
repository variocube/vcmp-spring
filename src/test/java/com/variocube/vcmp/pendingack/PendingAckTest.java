package com.variocube.vcmp.pendingack;

import com.variocube.vcmp.VcmpSession;
import com.variocube.vcmp.VcmpTestBase;
import com.variocube.vcmp.client.VcmpConnectionManager;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ProblemDetail;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Covers the guarantee that a pending {@link com.variocube.vcmp.VcmpCallback} is always settled
 * once its session is gone — directly for the sender, and through the chain for a listener that
 * deferred its ACK to a downstream session.
 * <p>
 * Each test owns its connections, so no session is left reconnecting for a following test.
 */
class PendingAckTest extends VcmpTestBase {

    private static final String URL = VcmpTestBase.BASE_URL + "/pendingack";

    @Autowired
    private PendingAckEndpoint endpoint;

    @Test
    void pendingCallbackIsFailedOnSessionClose() throws IOException {
        val client = new PendingAckClient();
        try (val connection = new VcmpConnectionManager(client, URL)) {
            connection.start();
            await().until(client::isConnected);

            // No second session, so the endpoint never ACKs this message.
            val nak = new AtomicReference<ProblemDetail>();
            client.send(new PendingAckMessage()).peekNak(nak::set);

            client.closeSession();

            await().until(() -> nak.get() != null);
            assertSessionClosed(nak.get());
        }
    }

    @Test
    void chainedCallbackIsFailedWhenDownstreamSessionCloses() throws IOException {
        val upstream = new PendingAckClient();
        val downstream = new PendingAckClient();
        try (val upstreamConnection = new VcmpConnectionManager(upstream, URL);
             val downstreamConnection = new VcmpConnectionManager(downstream, URL)) {

            upstreamConnection.start();
            downstreamConnection.start();
            await().until(upstream::isConnected);
            await().until(downstream::isConnected);
            await().until(() -> endpoint.getSessionPool().getOpenSessions().size() == 2);

            // The endpoint forwards to the downstream session and defers its ACK to that
            // callback; the downstream client never ACKs.
            val nak = new AtomicReference<ProblemDetail>();
            upstream.send(new PendingAckMessage()).peekNak(nak::set);

            // Only once the downstream has the message is there a pending callback to fail;
            // closing earlier would leave the endpoint with nothing to forward to.
            await().until(downstream::hasReceivedMessage);

            // Closing the downstream connection must fail the endpoint's chained callback,
            // which propagates back to the upstream sender as a NAK frame.
            downstreamConnection.close();

            await().until(() -> nak.get() != null);
            assertSessionClosed(nak.get());
        }
    }

    @Test
    void sendOnClosedSessionFailsImmediately() throws IOException {
        val client = new PendingAckClient();
        VcmpSession serverSession;
        try (val connection = new VcmpConnectionManager(client, URL)) {
            connection.start();
            await().until(client::isConnected);
            serverSession = endpoint.getSessionPool().getOpenSessions().get(0);
        }
        await().until(() -> !serverSession.isOpen());

        val nak = new AtomicReference<ProblemDetail>();
        serverSession.send(new PendingAckMessage()).peekNak(nak::set);

        assertThat(nak.get()).isNotNull();
        assertSessionClosed(nak.get());
    }

    private static void assertSessionClosed(ProblemDetail problemDetail) {
        assertThat(problemDetail.getStatus()).isEqualTo(503);
        assertThat(problemDetail.getTitle()).isEqualTo("Session closed");
    }

}
