package com.variocube.vcmp.retry;

import com.variocube.vcmp.VcmpListener;
import com.variocube.vcmp.server.VcmpEndpoint;
import lombok.Getter;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fails the first two invocations per message with a transient error and succeeds on the
 * third — the shape of a commit-time lock conflict that listener retry is meant to absorb.
 */
@VcmpEndpoint(path = "/retry")
public class RetryEndpoint {

    @Getter
    private final AtomicInteger invocations = new AtomicInteger();

    @VcmpListener(retry = true)
    public void handleRetryMessage(RetryMessage message) {
        if (invocations.incrementAndGet() < 3) {
            throw new IllegalStateException("transient failure " + invocations.get());
        }
    }

}
