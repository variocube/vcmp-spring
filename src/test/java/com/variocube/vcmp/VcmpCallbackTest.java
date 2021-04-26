package com.variocube.vcmp;


import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class VcmpCallbackTest {

    @Test
    public void canAckFuture() throws ExecutionException, InterruptedException {
        VcmpCallback callback = new VcmpCallback();
        CompletableFuture<Void> future = callback.toCompletableFuture();
        callback.notifyAck();
        future.get();
    }

    @Test
    public void canAckFutureAsync() throws ExecutionException, InterruptedException {
        VcmpCallback callback = new VcmpCallback();
        CompletableFuture<Void> future = callback.toCompletableFuture();
        callback.notifyAck();
        future.get();
    }

    @Test
    public void canNakFuture() {
        VcmpCallback callback = new VcmpCallback();
        CompletableFuture<Void> future = callback.toCompletableFuture();
        callback.notifyNak();
        assertThatThrownBy(future::get);
    }

    @Test
    public void canAwaitAck() {
        VcmpCallback callback = new VcmpCallback();
        new Thread(callback::notifyAck).start();
        callback.await();
    }

    @Test
    public void canAwaitNak() {
        VcmpCallback callback = new VcmpCallback();
        new Thread(callback::notifyNak).start();
        assertThatThrownBy(callback::await);
    }

    @Test
    public void canAwaitCompleted() {
        VcmpCallback.completed().await();
    }

    @Test
    public void canAwaitFailed() {
        assertThatThrownBy(() -> VcmpCallback.failed().await());
    }

}
