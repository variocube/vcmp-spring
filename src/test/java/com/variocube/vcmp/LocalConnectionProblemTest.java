package com.variocube.vcmp;

import lombok.val;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class LocalConnectionProblemTest {

    @Test
    void markSetsPropertyAndIsMarkedDetectsIt() {
        val problemDetail = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(LocalConnectionProblem.isMarked(problemDetail)).isFalse();

        val marked = LocalConnectionProblem.mark(problemDetail);

        assertThat(marked).isSameAs(problemDetail);
        assertThat(LocalConnectionProblem.isMarked(marked)).isTrue();
    }

    @Test
    void isMarkedIsNullSafe() {
        assertThat(LocalConnectionProblem.isMarked(null)).isFalse();

        val withoutProperties = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        assertThat(LocalConnectionProblem.isMarked(withoutProperties)).isFalse();

        val withOtherProperties = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        withOtherProperties.setProperty("foo", "bar");
        assertThat(LocalConnectionProblem.isMarked(withOtherProperties)).isFalse();
    }

    @Test
    void unmarkRemovesMarkerAndIsIdempotent() {
        val problemDetail = LocalConnectionProblem.mark(ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE));

        LocalConnectionProblem.unmark(problemDetail);
        assertThat(LocalConnectionProblem.isMarked(problemDetail)).isFalse();

        // idempotent, and safe on ProblemDetails without properties
        LocalConnectionProblem.unmark(problemDetail);
        LocalConnectionProblem.unmark(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST));
    }

    @Test
    void stripForWireReturnsSameInstanceWhenUnmarked() {
        val problemDetail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);

        assertThat(LocalConnectionProblem.stripForWire(problemDetail)).isSameAs(problemDetail);
        assertThat(LocalConnectionProblem.stripForWire(null)).isNull();
    }

    @Test
    void stripForWireCopiesWithoutMarkerAndPreservesEverythingElse() {
        val problemDetail = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problemDetail.setType(URI.create("https://example.com/problem"));
        problemDetail.setTitle("Session closed");
        problemDetail.setDetail("The session was closed.");
        problemDetail.setInstance(URI.create("https://example.com/instance"));
        problemDetail.setProperty("foo", "bar");
        LocalConnectionProblem.mark(problemDetail);

        val stripped = LocalConnectionProblem.stripForWire(problemDetail);

        assertThat(stripped).isNotSameAs(problemDetail);
        assertThat(LocalConnectionProblem.isMarked(stripped)).isFalse();
        assertThat(stripped.getStatus()).isEqualTo(problemDetail.getStatus());
        assertThat(stripped.getType()).isEqualTo(problemDetail.getType());
        assertThat(stripped.getTitle()).isEqualTo(problemDetail.getTitle());
        assertThat(stripped.getDetail()).isEqualTo(problemDetail.getDetail());
        assertThat(stripped.getInstance()).isEqualTo(problemDetail.getInstance());
        assertThat(stripped.getProperties()).containsEntry("foo", "bar");

        // the original stays marked: it may be shared with concurrently running local NAK handlers
        assertThat(LocalConnectionProblem.isMarked(problemDetail)).isTrue();
    }

    @Test
    void stripForWireOutputSerializesWithoutMarker() throws Exception {
        val problemDetail = LocalConnectionProblem.mark(ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE));
        problemDetail.setTitle("Session closed");

        // exactly what VcmpHandler.nak serializes into the NAK frame
        val payload = ObjectMapperHolder.createObjectMapper()
                .writeValueAsString(LocalConnectionProblem.stripForWire(problemDetail));

        assertThat(payload).doesNotContain(LocalConnectionProblem.PROPERTY);
    }

    @Test
    void isTransportFailureClassifiesByOrigin() {
        // locally thrown by VcmpCallback.await on timeout/interrupt
        assertThat(LocalConnectionProblem.isTransportFailure(
                new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT, "Timeout while waiting for response.")
        )).isTrue();

        // locally detected connection problem, whatever the status
        assertThat(LocalConnectionProblem.isTransportFailure(
                errorResponse(LocalConnectionProblem.mark(ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR)))
        )).isTrue();

        // peer NAKs are not transport failures, whatever their status code
        assertThat(LocalConnectionProblem.isTransportFailure(
                errorResponse(ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE))
        )).isFalse();
        assertThat(LocalConnectionProblem.isTransportFailure(
                errorResponse(ProblemDetail.forStatus(HttpStatus.REQUEST_TIMEOUT))
        )).isFalse();
        assertThat(LocalConnectionProblem.isTransportFailure(
                errorResponse(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST))
        )).isFalse();
    }

    private static ErrorResponseException errorResponse(ProblemDetail problemDetail) {
        return new ErrorResponseException(HttpStatus.valueOf(problemDetail.getStatus()), problemDetail, null);
    }
}
