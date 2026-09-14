package com.variocube.vcmp;

import com.variocube.vcmp.error.AnnotatedNotFoundException;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/** The mapping in isolation: which exception is looked at, and what status it yields. */
class VcmpHandlerProblemDetailTest {

    @Test
    void unwrapStripsTheFutureMachinery() {
        val cause = new IllegalStateException("the listener's own failure");

        assertThat(VcmpHandler.unwrap(new CompletionException(cause))).isSameAs(cause);
        assertThat(VcmpHandler.unwrap(new CompletionException(new ExecutionException(cause)))).isSameAs(cause);
        assertThat(VcmpHandler.unwrap(cause)).isSameAs(cause);
    }

    @Test
    void unwrapKeepsAWrapperWithoutACause() {
        val bare = new CompletionException("no cause", null);
        assertThat(VcmpHandler.unwrap(bare)).isSameAs(bare);
    }

    @Test
    void deliberateStatusIsPreservedOnceUnwrapped() {
        val wrapped = new CompletionException(new ResponseStatusException(HttpStatus.BAD_REQUEST, "deliberate"));

        assertThat(VcmpHandler.createProblemDetail(VcmpHandler.unwrap(wrapped)).getStatus()).isEqualTo(400);
        // and the wrapper itself would have been the generic failure — the bug this fixes
        assertThat(VcmpHandler.createProblemDetail(wrapped).getStatus()).isEqualTo(500);
    }

    @Test
    void responseStatusAnnotationYieldsItsCodeAndTheMessageAsDetail() {
        val problemDetail = VcmpHandler.createProblemDetail(new AnnotatedNotFoundException("gone"));

        assertThat(problemDetail.getStatus()).isEqualTo(404);
        assertThat(problemDetail.getDetail()).isEqualTo("gone");
    }

    @Test
    void unclassifiedFailureStaysAGenericFive00() {
        val problemDetail = VcmpHandler.createProblemDetail(new RuntimeException("boom"));

        assertThat(problemDetail.getStatus()).isEqualTo(500);
        assertThat(problemDetail.getTitle()).isEqualTo("Message handling failed");
    }
}
