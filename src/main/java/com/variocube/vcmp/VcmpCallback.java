package com.variocube.vcmp;

import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
public class VcmpCallback {

    private enum State {
        Pending,
        Completed,
        Failed,
    }

    private volatile State state = State.Pending;

    private Runnable ack;
    private Runnable nak;

    public VcmpCallback onAck(Runnable ack) {
        // set the ACK handler only if execution is pending
        if (this.state == State.Pending) {
            if (this.ack != null) {
                throw new RuntimeException("Only one onAck handler is supported.");
            }
            this.ack = ack;
        }
        // Execute the handler directly if the execution completed
        // Keep this as separate `if` to avoid race condition if `notifyAck` was called
        // during execution of the code above
        if (this.state == State.Completed) {
            ack.run();
        }
        return this;
    }

    public VcmpCallback onNak(Runnable nak) {
        // set the NAK handler only if execution is pending
        if (this.state == State.Pending) {
            if (this.nak != null) {
                throw new RuntimeException("Only one onNak handler is supported.");
            }
            this.nak = nak;
        }
        // Execute the handler directly if the execution failed
        // Keep this as separate `if` to avoid race condition if `notifyNak` was called
        // during execution of the code above
        if (this.state == State.Failed) {
            nak.run();
        }
        return this;
    }

    void notifyAck() {
        this.state = State.Completed;
        if (this.ack != null) {
            log.debug("Invoking ACK handler.");
            this.ack.run();
        }
    }

    void notifyNak() {
        this.state = State.Failed;
        if (this.nak != null) {
            log.debug("Invoking NAK handler.");
            this.nak.run();
        }
    }

    public CompletableFuture<Void> toCompletableFuture() {
        CompletableFuture<Void> completableFuture = new CompletableFuture<>();
        this.onAck(() -> completableFuture.complete(null));
        this.onNak(() -> completableFuture.completeExceptionally(new RuntimeException("NAK")));
        return completableFuture;
    }

    public void await() {
        toCompletableFuture().join();
    }

    public void await(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        toCompletableFuture().get(timeout, unit);
    }

    public static VcmpCallback completed() {
        val callback = new VcmpCallback();
        callback.state = State.Completed;
        return callback;
    }

    public static VcmpCallback failed() {
        val callback = new VcmpCallback();
        callback.state = State.Failed;
        return callback;
    }
}
