package com.variocube.vcmp.retry;

import com.variocube.vcmp.VcmpTestBase;
import com.variocube.vcmp.client.VcmpConnectionManager;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ProblemDetail;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end check of listener retry (variocube/center#450) over a real websocket: a
 * {@code @VcmpListener(retry = true)} listener failing transiently is retried by the
 * server and the sender observes a single ACK — no NAK ever reaches it.
 */
class RetryTest extends VcmpTestBase {

    private static final String URL = VcmpTestBase.BASE_URL + "/retry";

    @Autowired
    private RetryEndpoint endpoint;

    @Test
    void transientListenerFailureIsRetriedAndAcked() throws Exception {
        val client = new RetryClient();
        try (val connection = new VcmpConnectionManager(client, URL)) {
            connection.start();
            await().until(client::isConnected);

            val acked = new AtomicBoolean();
            val nak = new AtomicReference<ProblemDetail>();
            client.send(new RetryMessage())
                    .peekAck(result -> acked.set(true))
                    .peekNak(nak::set);

            await().untilTrue(acked);
            assertThat(endpoint.getInvocations().get()).isEqualTo(3);
            assertThat(nak.get()).isNull();
        }
    }

}
