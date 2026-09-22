package com.variocube.vcmp.error;

import com.variocube.vcmp.LocalConnectionProblem;
import com.variocube.vcmp.VcmpCallback;
import com.variocube.vcmp.VcmpListener;
import com.variocube.vcmp.server.VcmpEndpoint;
import lombok.val;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.CompletableFuture;

@VcmpEndpoint(path = "/errorVcmp")
public class ErrorEndpoint {

    @VcmpListener
    public void throwException(TestMessage testMessage) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This is bad");
    }

    @VcmpListener
    public VcmpCallback<Void> failCallback(FailedCallbackMessage message) {
        // Returns a callback failed with an explicitly marked ProblemDetail that the framework
        // chains into the outbound NAK — the marker must be stripped at the wire boundary.
        return VcmpCallback.failed(
                LocalConnectionProblem.mark(ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR)));
    }

    @VcmpListener
    public VcmpCallback<ProblemDetail> ackWithProblem(AckProblemMessage message) {
        // Relay pattern: ACKs with a locally marked ProblemDetail as the RESULT — the marker must be
        // stripped from the ACK frame just like from NAK frames.
        val problemDetail = LocalConnectionProblem.mark(
                ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE));
        problemDetail.setTitle("Session closed");
        return VcmpCallback.completed(problemDetail);
    }

    /**
     * The async shape: the future fails with the listener's deliberate status. VcmpHandler sees it
     * through a dependent stage, i.e. wrapped in a CompletionException, and must still map the 400.
     */
    @VcmpListener
    public CompletableFuture<Void> failAsync(AsyncTestMessage message) {
        return CompletableFuture.failedFuture(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "This is bad, asynchronously"));
    }

    /**
     * The sync shape of the same wrapper: a listener that joins a failed future throws the
     * CompletionException itself, on the invoking thread.
     */
    @VcmpListener
    public void joinFailedFuture(JoinedFutureTestMessage message) {
        CompletableFuture.failedFuture(new ResponseStatusException(HttpStatus.BAD_REQUEST, "This is bad, joined"))
                .join();
    }

    @VcmpListener
    public void throwAnnotated(AnnotatedTestMessage message) {
        throw new AnnotatedNotFoundException("Nothing here");
    }

    @VcmpListener
    public CompletableFuture<Void> failAnnotatedAsync(AnnotatedAsyncTestMessage message) {
        return CompletableFuture.failedFuture(new AnnotatedNotFoundException("Nothing here either"));
    }

    @VcmpListener
    public void crash(CrashTestMessage message) {
        throw new IllegalStateException("Nobody meant this");
    }
}
