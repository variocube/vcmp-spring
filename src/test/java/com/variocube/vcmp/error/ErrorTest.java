package com.variocube.vcmp.error;

import com.variocube.vcmp.LocalConnectionProblem;
import com.variocube.vcmp.VcmpTestBase;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;

class ErrorTest extends VcmpTestBase {

    @Autowired
    private ErrorClient client;

    @Test
    void testError() {
        await().until(client::isConnected);
        // default await timeout: the NAK arrives as soon as the listener throws, so this costs
        // nothing on the happy path and avoids CI flakes on slow runners
        val exception = catchThrowableOfType(() -> client.send(new TestMessage()).await(), ErrorResponseException.class);

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception.getBody().getDetail()).isEqualTo("This is bad");
        // a genuine peer rejection is never a local connection problem
        assertThat(LocalConnectionProblem.isMarked(exception.getBody())).isFalse();
    }

    @Test
    void markedProblemDetailArrivesAtPeerWithoutMarker() {
        await().until(client::isConnected);
        val exception = catchThrowableOfType(
                () -> client.send(new FailedCallbackMessage()).await(),
                ErrorResponseException.class);

        // The endpoint's ProblemDetail is explicitly marked on the server, but the marker must not
        // survive the NAK frame: to this client it is a peer rejection.
        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(LocalConnectionProblem.isMarked(exception.getBody())).isFalse();
    }

    @Test
    void markedProblemDetailAckResultArrivesAtPeerWithoutMarker() {
        await().until(client::isConnected);

        // The endpoint ACKs with a marked ProblemDetail as the RESULT — the marker must not be
        // observable on the ACK channel either.
        val result = client.send(new AckProblemMessage(), ProblemDetail.class)
                .await();

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(503);
        assertThat(result.getTitle()).isEqualTo("Session closed");
        assertThat(LocalConnectionProblem.isMarked(result)).isFalse();
    }
}
