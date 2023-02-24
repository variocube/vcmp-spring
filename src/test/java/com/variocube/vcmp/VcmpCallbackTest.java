package com.variocube.vcmp;


import lombok.val;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    public void canAwaitAckTimeout() throws ExecutionException, InterruptedException, TimeoutException {
        VcmpCallback callback = new VcmpCallback();
        new Thread(callback::notifyAck).start();
        callback.await(1, TimeUnit.SECONDS);
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

    @Test
    public void canAllImmediateAck() {
        VcmpCallback.all(VcmpCallback.completed(), VcmpCallback.completed()).await();
    }

    @Test
    public void canAllImmediateNak() {
        val callback = VcmpCallback.all(VcmpCallback.completed(), VcmpCallback.failed());
        assertThatThrownBy(callback::await);
    }

    @Test
    public void canAllLaterAck() {
        val success1 = new VcmpCallback();
        val success2 = new VcmpCallback();
        val success3 = new VcmpCallback();
        val all = VcmpCallback.all(success1, success2, success3);
        new Thread(success1::notifyAck).start();
        new Thread(success2::notifyAck).start();
        new Thread(success3::notifyAck).start();
        all.await();
    }

    @Test
    public void canAllLaterNak() {
        val success1 = new VcmpCallback();
        val success2 = new VcmpCallback();
        val failure = new VcmpCallback();
        val all = VcmpCallback.all(success1, success2, failure);
        new Thread(success1::notifyAck).start();
        new Thread(success2::notifyAck).start();
        new Thread(failure::notifyNak).start();
        assertThatThrownBy(all::await);
    }
}
