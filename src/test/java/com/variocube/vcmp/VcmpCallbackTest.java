package com.variocube.vcmp;


import lombok.val;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

class VcmpCallbackTest {

    @Test
    void canAckFuture() throws ExecutionException, InterruptedException {
        val callback = new VcmpCallback<Void>();
        CompletableFuture<Void> future = callback.toCompletableFuture();
        callback.notifyAck(null);
        future.get();
    }

    @Test
    void canAckFutureAsync() throws ExecutionException, InterruptedException {
        val callback = new VcmpCallback<Void>();
        CompletableFuture<Void> future = callback.toCompletableFuture();
        new Thread(() -> callback.notifyAck(null)).start();
        future.get();
    }

    @Test
    void canNakFuture() {
        val callback = new VcmpCallback<Void>();
        CompletableFuture<Void> future = callback.toCompletableFuture();
        callback.notifyNak(ProblemDetail.forStatus(HttpStatus.CONFLICT));

        val exception = catchThrowableOfType(future::get, ExecutionException.class);
        assertThat(exception.getCause())
                .isInstanceOf(ErrorResponseException.class);
    }

    @Test
    void canAwaitAck() {
        val callback = new VcmpCallback<Integer>();
        new Thread(() -> callback.notifyAck(5)).start();
        assertThat(callback.await()).isEqualTo(5);
    }

    @Test
    void canAwaitAckTimeout() throws ExecutionException, InterruptedException, TimeoutException {
        val callback = new VcmpCallback<Integer>();
        new Thread(() -> callback.notifyAck(5)).start();
        assertThat(callback.await(1, TimeUnit.SECONDS)).isEqualTo(5);
    }

    @Test
    void canAwaitNak() {
        val callback = new VcmpCallback<Void>();
        new Thread(() -> callback.notifyNak(ProblemDetail.forStatus(HttpStatus.CONFLICT))).start();
        val exception = catchThrowableOfType(callback::await, ErrorResponseException.class);
        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void canAwaitCompleted() {
        VcmpCallback.completed().await();
    }

    @Test
    void canAwaitCompletedValue() {
        assertThat(VcmpCallback.completed(5).await()).isEqualTo(5);
    }

    @Test
    void canAwaitFailed() {
        val exception = catchThrowableOfType(() -> VcmpCallback.failed().await(), ErrorResponseException.class);
        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void canAwaitFailedProblemDetail() {
        val exception = catchThrowableOfType(() -> VcmpCallback.failed(ProblemDetail.forStatus(HttpStatus.CONFLICT)).await(), ErrorResponseException.class);
        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void canAllImmediateAck() {
        val results = VcmpCallback.all(VcmpCallback.completed(), VcmpCallback.completed()).await();
        assertThat(results).containsExactly(null, null);
    }

    @Test
    void canAllImmediateNak() {
        val callback = VcmpCallback.all(VcmpCallback.completed(), VcmpCallback.failed());
        assertThatThrownBy(callback::await);
    }

    @Test
    void canAllLaterAck() {
        val success1 = new VcmpCallback<Void>();
        val success2 = new VcmpCallback<Void>();
        val success3 = new VcmpCallback<Void>();
        val all = VcmpCallback.all(success1, success2, success3);
        new Thread(() -> success1.notifyAck(null)).start();
        new Thread(() -> success2.notifyAck(null)).start();
        new Thread(() -> success3.notifyAck(null)).start();
        all.await();
    }

    @Test
    void canAllLaterNak() {
        val success1 = new VcmpCallback<Void>();
        val success2 = new VcmpCallback<Void>();
        val failure = new VcmpCallback<Void>();
        val all = VcmpCallback.all(success1, success2, failure);
        new Thread(() -> success1.notifyAck(null)).start();
        new Thread(() -> success2.notifyAck(null)).start();
        new Thread(() -> failure.notifyNak(ProblemDetail.forStatus(HttpStatus.CONFLICT))).start();
        val exception = catchThrowableOfType(all::await, ErrorResponseException.class);
        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
