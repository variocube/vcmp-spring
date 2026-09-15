package com.variocube.vcmp.error;

import com.variocube.vcmp.VcmpListener;
import com.variocube.vcmp.server.VcmpEndpoint;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.CompletableFuture;

@VcmpEndpoint(path = "/errorVcmp")
public class ErrorEndpoint {

    @VcmpListener
    public void throwException(TestMessage testMessage) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This is bad");
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
