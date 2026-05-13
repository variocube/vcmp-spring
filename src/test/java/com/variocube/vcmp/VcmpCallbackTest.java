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
import java.util.concurrent.atomic.AtomicReference;

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

    @Test
    void canMapSync() {
        val original = VcmpCallback.completed("foo");
        val mapped = original.map(String::toUpperCase);
        assertThat(mapped.await()).isEqualTo("FOO");
    }

    @Test
    void canMapAsync() {
        val original = new VcmpCallback<String>();
        val mapped = original.map(String::toUpperCase);
        new Thread(() -> original.notifyAck("foo")).start();
        assertThat(mapped.await()).isEqualTo("FOO");
    }

    @Test
    void canMapWithNakHandlerSync() {
        val seen = new AtomicReference<ProblemDetail>();
        val original = VcmpCallback.<String>failed(ProblemDetail.forStatus(HttpStatus.CONFLICT));
        val mapped = original.map(String::toUpperCase, seen::set);
        val exception = catchThrowableOfType(mapped::await, ErrorResponseException.class);
        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(seen.get().getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void canMapWithNakHandlerForwardsAck() {
        val seen = new AtomicReference<ProblemDetail>();
        val original = VcmpCallback.completed("foo");
        val mapped = original.map(String::toUpperCase, seen::set);
        assertThat(mapped.await()).isEqualTo("FOO");
        assertThat(seen.get()).isNull();
    }

    @Test
    void canPeekAckSync() {
        val seen = new AtomicReference<String>();
        val peeked = VcmpCallback.completed("foo").peekAck(seen::set);
        assertThat(peeked.await()).isEqualTo("foo");
        assertThat(seen.get()).isEqualTo("foo");
    }

    @Test
    void canPeekAckAsync() {
        val seen = new AtomicReference<String>();
        val original = new VcmpCallback<String>();
        val peeked = original.peekAck(seen::set);
        new Thread(() -> original.notifyAck("foo")).start();
        assertThat(peeked.await()).isEqualTo("foo");
        assertThat(seen.get()).isEqualTo("foo");
    }

    @Test
    void peekAckDoesNotFireOnNak() {
        val seen = new AtomicReference<String>();
        val original = VcmpCallback.<String>failed(ProblemDetail.forStatus(HttpStatus.CONFLICT));
        val peeked = original.peekAck(seen::set);
        assertThatThrownBy(peeked::await).isInstanceOf(ErrorResponseException.class);
        assertThat(seen.get()).isNull();
    }

    @Test
    void canPeekNakSync() {
        val seen = new AtomicReference<ProblemDetail>();
        val original = VcmpCallback.<String>failed(ProblemDetail.forStatus(HttpStatus.CONFLICT));
        val peeked = original.peekNak(seen::set);
        val exception = catchThrowableOfType(peeked::await, ErrorResponseException.class);
        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(seen.get().getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void canPeekNakAsync() {
        val seen = new AtomicReference<ProblemDetail>();
        val original = new VcmpCallback<String>();
        val peeked = original.peekNak(seen::set);
        new Thread(() -> original.notifyNak(ProblemDetail.forStatus(HttpStatus.CONFLICT))).start();
        val exception = catchThrowableOfType(peeked::await, ErrorResponseException.class);
        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(seen.get().getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    }

    @Test
    void peekNakDoesNotFireOnAck() {
        val seen = new AtomicReference<ProblemDetail>();
        val peeked = VcmpCallback.completed("foo").peekNak(seen::set);
        assertThat(peeked.await()).isEqualTo("foo");
        assertThat(seen.get()).isNull();
    }

    /**
     * Reproduces the scenario from issue #11: a listener returns a callback after attaching
     * its own side effect via peekNak. The framework can still wire the terminal handler onto
     * the returned callback because peek produces a fresh callback with empty handler slots.
     */
    @Test
    void peekDoesNotCollideWithFrameworkHandler() {
        val sideEffectFired = new AtomicReference<Boolean>(false);
        val frameworkReceived = new AtomicReference<ProblemDetail>();
        val original = new VcmpCallback<String>();
        val returned = original.peekNak(error -> sideEffectFired.set(true));

        // Simulate VcmpHandler.handleMessagePayload attaching the terminal handler.
        assertThatNoException().isThrownBy(() -> returned.onNak(frameworkReceived::set));

        original.notifyNak(ProblemDetail.forStatus(HttpStatus.CONFLICT));
        assertThat(sideEffectFired.get()).isTrue();
        assertThat(frameworkReceived.get().getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
    }
}
