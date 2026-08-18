package com.variocube.vcmp.client;

import com.variocube.vcmp.LocalProblem;
import com.variocube.vcmp.VcmpMessage;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BasicVcmpClientTest {

    @Test
    void sendWithoutSessionFailsWithLocalNotConnectedProblem() {
        val client = new BasicVcmpClient();

        val nak = new AtomicReference<ProblemDetail>();
        client.send(new VcmpMessage() {
        }).peekNak(nak::set);

        assertThat(nak.get()).isNotNull();
        assertThat(nak.get().getStatus()).isEqualTo(503);
        assertThat(nak.get().getTitle()).isEqualTo("Not connected");
        assertThat(LocalProblem.isLocal(nak.get())).isTrue();
    }
}
