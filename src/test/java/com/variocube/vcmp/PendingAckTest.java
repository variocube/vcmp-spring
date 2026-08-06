package com.variocube.vcmp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ProblemDetail;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class PendingAckTest extends VcmpTestBase {

    @Autowired
    private PendingAckClient client;

    @AfterEach
    public void restoreAckTimeout() {
        VcmpSession.ackTimeoutSeconds = 30;
    }

    @Test
    public void pendingCallbackIsFailedOnSessionClose() throws IOException {
        // generous timeout: a previous test may have closed the session, and the
        // reconnect backoff is 10-30 seconds
        await().atMost(60, TimeUnit.SECONDS).until(client::isConnected);

        AtomicReference<ProblemDetail> nak = new AtomicReference<>();
        client.send(new PendingAckMessage()).peekNak(nak::set);

        client.closeSession();

        await().until(() -> nak.get() != null);
        assertThat(nak.get().getStatus()).isEqualTo(503);
        assertThat(nak.get().getTitle()).isEqualTo("Session closed");
    }

    @Test
    public void pendingCallbackIsFailedOnAckTimeout() {
        VcmpSession.ackTimeoutSeconds = 1;
        // generous timeout: a previous test may have closed the session, and the
        // reconnect backoff is 10-30 seconds
        await().atMost(60, TimeUnit.SECONDS).until(client::isConnected);

        AtomicReference<ProblemDetail> nak = new AtomicReference<>();
        client.send(new PendingAckMessage()).peekNak(nak::set);

        await().until(() -> nak.get() != null);
        assertThat(nak.get().getStatus()).isEqualTo(504);
        assertThat(nak.get().getTitle()).isEqualTo("Acknowledgement timeout");
    }

}
