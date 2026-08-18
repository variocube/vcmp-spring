package com.variocube.vcmp.error;

import com.variocube.vcmp.LocalProblem;
import com.variocube.vcmp.VcmpTestBase;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;

class ErrorTest extends VcmpTestBase {

    @Autowired
    private ErrorClient client;

    @Test
    void testError() {
        await().until(client::isConnected);
        val exception = catchThrowableOfType(() -> client.send(new TestMessage()).await(100, TimeUnit.MILLISECONDS), ErrorResponseException.class);

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception.getBody().getDetail()).isEqualTo("This is bad");
        // a genuine peer rejection is never local
        assertThat(LocalProblem.isLocal(exception.getBody())).isFalse();
    }

    @Test
    void locallyFailedCallbackArrivesAtPeerWithoutLocalMarker() {
        await().until(client::isConnected);
        val exception = catchThrowableOfType(
                () -> client.send(new FailedCallbackMessage()).await(100, TimeUnit.MILLISECONDS),
                ErrorResponseException.class);

        // The endpoint's VcmpCallback.failed() ProblemDetail is marked local on the server, but the
        // marker must not survive the NAK frame: to this client it is a peer rejection.
        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(LocalProblem.isLocal(exception.getBody())).isFalse();
    }
}
