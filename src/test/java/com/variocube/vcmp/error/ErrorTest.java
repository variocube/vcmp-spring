package com.variocube.vcmp.error;

import com.variocube.vcmp.VcmpMessage;
import com.variocube.vcmp.VcmpTestBase;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponseException;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;

/**
 * A listener's deliberate failure status must survive to the NAK on every path. Before this fix the
 * async path handed {@code createProblemDetail} the {@code CompletionException} from the dependent
 * stage, and a {@code @ResponseStatus}-annotated exception was not recognised at all — both arrived
 * at the peer as a generic 500, indistinguishable from a crash. The same distinction now governs
 * this side's log: a deliberate rejection is one line, an unclassified failure keeps its stack trace.
 */
@ExtendWith(OutputCaptureExtension.class)
class ErrorTest extends VcmpTestBase {

    @Autowired
    private ErrorClient client;

    @Test
    void testError() {
        val exception = nakFor(new TestMessage());

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception.getBody().getDetail()).isEqualTo("This is bad");
    }

    @Test
    void asyncListenerKeepsItsDeliberateStatus() {
        val exception = nakFor(new AsyncTestMessage());

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception.getBody().getDetail()).isEqualTo("This is bad, asynchronously");
    }

    @Test
    void syncListenerJoiningAFailedFutureKeepsItsDeliberateStatus() {
        val exception = nakFor(new JoinedFutureTestMessage());

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception.getBody().getDetail()).isEqualTo("This is bad, joined");
    }

    @Test
    void responseStatusAnnotationIsMappedOnTheSyncPath() {
        val exception = nakFor(new AnnotatedTestMessage());

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exception.getBody().getDetail()).isEqualTo("Nothing here");
    }

    @Test
    void responseStatusAnnotationIsMappedThroughTheAsyncWrapper() {
        val exception = nakFor(new AnnotatedAsyncTestMessage());

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exception.getBody().getDetail()).isEqualTo("Nothing here either");
    }

    @Test
    void deliberateFailureIsLoggedAsARejectionWithoutAStackTrace(CapturedOutput output) {
        nakFor(new AnnotatedTestMessage());

        await().untilAsserted(() -> assertThat(output).contains("with status 404: Nothing here"));
        // the stack frame of the throwing listener only shows up if the exception itself was logged
        assertThat(output).doesNotContain("ErrorEndpoint.throwAnnotated");
    }

    @Test
    void unclassifiedFailureIsA500AndIsLoggedWithItsStackTrace(CapturedOutput output) {
        val exception = nakFor(new CrashTestMessage());

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(exception.getBody().getTitle()).isEqualTo("Message handling failed");
        assertThat(exception.getBody().getDetail()).isEqualTo("Nobody meant this");
        await().untilAsserted(() -> assertThat(output)
                .contains("ERROR")
                .contains("IllegalStateException: Nobody meant this")
                .contains("ErrorEndpoint.crash"));
    }

    private ErrorResponseException nakFor(VcmpMessage message) {
        await().until(client::isConnected);
        return catchThrowableOfType(() -> client.send(message).await(1, TimeUnit.SECONDS),
                ErrorResponseException.class);
    }
}
