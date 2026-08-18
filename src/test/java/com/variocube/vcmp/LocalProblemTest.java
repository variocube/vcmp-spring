package com.variocube.vcmp;

import lombok.val;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class LocalProblemTest {

    @Test
    void markSetsPropertyAndIsLocalDetectsIt() {
        val problemDetail = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(LocalProblem.isLocal(problemDetail)).isFalse();

        val marked = LocalProblem.mark(problemDetail);

        assertThat(marked).isSameAs(problemDetail);
        assertThat(LocalProblem.isLocal(marked)).isTrue();
    }

    @Test
    void isLocalIsNullSafe() {
        assertThat(LocalProblem.isLocal(null)).isFalse();

        val withoutProperties = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        assertThat(LocalProblem.isLocal(withoutProperties)).isFalse();

        val withOtherProperties = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        withOtherProperties.setProperty("foo", "bar");
        assertThat(LocalProblem.isLocal(withOtherProperties)).isFalse();
    }

    @Test
    void unmarkRemovesMarkerAndIsIdempotent() {
        val problemDetail = LocalProblem.mark(ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE));

        LocalProblem.unmark(problemDetail);
        assertThat(LocalProblem.isLocal(problemDetail)).isFalse();

        // idempotent, and safe on ProblemDetails without properties
        LocalProblem.unmark(problemDetail);
        LocalProblem.unmark(ProblemDetail.forStatus(HttpStatus.BAD_REQUEST));
    }

    @Test
    void stripForWireReturnsSameInstanceWhenUnmarked() {
        val problemDetail = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);

        assertThat(LocalProblem.stripForWire(problemDetail)).isSameAs(problemDetail);
        assertThat(LocalProblem.stripForWire(null)).isNull();
    }

    @Test
    void stripForWireCopiesWithoutMarkerAndPreservesEverythingElse() {
        val problemDetail = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problemDetail.setType(URI.create("https://example.com/problem"));
        problemDetail.setTitle("Session closed");
        problemDetail.setDetail("The session was closed.");
        problemDetail.setInstance(URI.create("https://example.com/instance"));
        problemDetail.setProperty("foo", "bar");
        LocalProblem.mark(problemDetail);

        val stripped = LocalProblem.stripForWire(problemDetail);

        assertThat(stripped).isNotSameAs(problemDetail);
        assertThat(LocalProblem.isLocal(stripped)).isFalse();
        assertThat(stripped.getStatus()).isEqualTo(problemDetail.getStatus());
        assertThat(stripped.getType()).isEqualTo(problemDetail.getType());
        assertThat(stripped.getTitle()).isEqualTo(problemDetail.getTitle());
        assertThat(stripped.getDetail()).isEqualTo(problemDetail.getDetail());
        assertThat(stripped.getInstance()).isEqualTo(problemDetail.getInstance());
        assertThat(stripped.getProperties()).containsEntry("foo", "bar");

        // the original stays marked: it may be shared with concurrently running local NAK handlers
        assertThat(LocalProblem.isLocal(problemDetail)).isTrue();
    }
}
