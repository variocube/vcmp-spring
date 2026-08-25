package com.variocube.vcmp;

import com.fasterxml.jackson.annotation.JsonTypeName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bounded listener retry (variocube/center#450): a listener that opted in with
 * {@code @VcmpListener(retry = true)} is retried with backoff on transient failure
 * before the message is NAKed. Deliberate error statuses and failures without opt-in
 * keep the immediate-NAK semantics.
 */
class ListenerRetryTest {

    private static final String MSG_ID = "AAAAAAAAAAAA";

    @JsonTypeName("retry:Transient")
    static class TransientMessage implements VcmpMessage {
    }

    @JsonTypeName("retry:Plain")
    static class PlainMessage implements VcmpMessage {
    }

    @JsonTypeName("retry:BadRequest")
    static class BadRequestMessage implements VcmpMessage {
    }

    @JsonTypeName("retry:Annotated")
    static class AnnotatedMessage implements VcmpMessage {
    }

    @JsonTypeName("retry:Future")
    static class FutureMessage implements VcmpMessage {
    }

    @JsonTypeName("retry:WrappedBadRequest")
    static class WrappedBadRequestMessage implements VcmpMessage {
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    static class AnnotatedNotFoundException extends RuntimeException {
        AnnotatedNotFoundException() {
            super("deliberately not found");
        }
    }

    static class Target {
        final AtomicInteger invocations = new AtomicInteger();
        volatile int failUntilAttempt;

        @VcmpListener(retry = true)
        public void handleTransient(TransientMessage message) {
            if (invocations.incrementAndGet() <= failUntilAttempt) {
                throw new RuntimeException("transient failure " + invocations.get());
            }
        }

        @VcmpListener
        public void handlePlain(PlainMessage message) {
            invocations.incrementAndGet();
            throw new RuntimeException("plain failure");
        }

        @VcmpListener(retry = true)
        public void handleBadRequest(BadRequestMessage message) {
            invocations.incrementAndGet();
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This is bad");
        }

        @VcmpListener(retry = true)
        public void handleAnnotated(AnnotatedMessage message) {
            invocations.incrementAndGet();
            throw new AnnotatedNotFoundException();
        }

        @VcmpListener(retry = true)
        public void handleWrappedBadRequest(WrappedBadRequestMessage message) {
            invocations.incrementAndGet();
            // the shape thrown by joining a failed future internally
            throw new CompletionException(new ResponseStatusException(HttpStatus.BAD_REQUEST, "wrapped but deliberate"));
        }

        @VcmpListener(retry = true)
        public CompletableFuture<Void> handleFuture(FutureMessage message) {
            if (invocations.incrementAndGet() <= failUntilAttempt) {
                return CompletableFuture.failedFuture(new RuntimeException("future failure " + invocations.get()));
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    void retriesTransientFailureThenAcks() throws Exception {
        Fixture fixture = new Fixture();
        fixture.target.failUntilAttempt = 2;

        fixture.receive("{\"@type\":\"retry:Transient\"}");

        await().until(() -> !fixture.sentFrames.isEmpty());
        assertThat(fixture.target.invocations.get()).isEqualTo(3);
        assertThat(fixture.sentFrames).hasSize(1);
        assertThat(fixture.sentFrames.get(0)).startsWith("ACK" + MSG_ID);
    }

    @Test
    void naksWithLastFailureAfterExhaustedAttempts() throws Exception {
        Fixture fixture = new Fixture();
        fixture.handler.setListenerRetryAttempts(3);
        fixture.target.failUntilAttempt = Integer.MAX_VALUE;

        fixture.receive("{\"@type\":\"retry:Transient\"}");

        await().until(() -> !fixture.sentFrames.isEmpty());
        assertThat(fixture.target.invocations.get()).isEqualTo(3);
        assertThat(fixture.sentFrames).hasSize(1);
        ProblemDetail problemDetail = fixture.assertSingleNak();
        assertThat(problemDetail.getDetail()).isEqualTo("transient failure 3");
    }

    @Test
    void doesNotRetryWithoutOptIn() throws Exception {
        Fixture fixture = new Fixture();

        fixture.receive("{\"@type\":\"retry:Plain\"}");

        await().until(() -> !fixture.sentFrames.isEmpty());
        assertThat(fixture.target.invocations.get()).isEqualTo(1);
        ProblemDetail problemDetail = fixture.assertSingleNak();
        assertThat(problemDetail.getDetail()).isEqualTo("plain failure");
    }

    @Test
    void doesNotRetryErrorResponseException() throws Exception {
        Fixture fixture = new Fixture();

        fixture.receive("{\"@type\":\"retry:BadRequest\"}");

        await().until(() -> !fixture.sentFrames.isEmpty());
        assertThat(fixture.target.invocations.get()).isEqualTo(1);
        ProblemDetail problemDetail = fixture.assertSingleNak();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problemDetail.getDetail()).isEqualTo("This is bad");
    }

    @Test
    void doesNotRetryResponseStatusAnnotatedException() throws Exception {
        Fixture fixture = new Fixture();

        fixture.receive("{\"@type\":\"retry:Annotated\"}");

        await().until(() -> !fixture.sentFrames.isEmpty());
        assertThat(fixture.target.invocations.get()).isEqualTo(1);
        ProblemDetail problemDetail = fixture.assertSingleNak();
        // The deliberate status must survive into the NAK — fast-fail classification and NAK
        // payload share one resolver, so what skips retry also reports its chosen status.
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problemDetail.getDetail()).isEqualTo("deliberately not found");
    }

    /**
     * A retry scheduled while the session was open must not run once it has closed: the sender
     * replays un-ACKed messages on reconnect, and a late attempt would race that replay.
     */
    @Test
    void abandonsScheduledRetryWhenSessionCloses() throws Exception {
        Fixture fixture = new Fixture();
        // Wide backoff so the session reliably closes before the scheduled retry fires.
        fixture.handler.setListenerRetryInitialDelayMs(500);
        fixture.target.failUntilAttempt = Integer.MAX_VALUE;

        fixture.receive("{\"@type\":\"retry:Transient\"}");

        await().until(() -> fixture.target.invocations.get() == 1);
        fixture.open.set(false);

        // The scheduled retry fires ~500 ms in; it must neither invoke the listener nor NAK.
        await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(2))
                .until(() -> fixture.target.invocations.get() == 1);
        assertThat(fixture.sentFrames).isEmpty();
    }

    /**
     * A failure while *sending* the ACK is a transport problem, not a listener failure: the
     * listener's effects are already committed, so a retry would duplicate them.
     */
    @Test
    void ackSendFailureIsNotRetried() throws Exception {
        Fixture fixture = new Fixture();
        fixture.failSends = true;

        fixture.receive("{\"@type\":\"retry:Transient\"}");

        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2))
                .until(() -> fixture.target.invocations.get() == 1);
        assertThat(fixture.sentFrames).isEmpty();
    }

    @Test
    void retryDelayIsClampedAgainstOverflowAndExcess() {
        assertThat(VcmpHandler.computeRetryDelay(100, 1)).isEqualTo(100);
        assertThat(VcmpHandler.computeRetryDelay(100, 4)).isEqualTo(800);
        // 100 << 7 = 12800 exceeds the cap
        assertThat(VcmpHandler.computeRetryDelay(100, 8)).isEqualTo(VcmpHandler.MAX_LISTENER_RETRY_DELAY_MS);
        // an unclamped shift would wrap mod 64 back to a small delay here
        assertThat(VcmpHandler.computeRetryDelay(100, 65)).isEqualTo(VcmpHandler.MAX_LISTENER_RETRY_DELAY_MS);
        // an oversized configured initial delay is capped as well
        assertThat(VcmpHandler.computeRetryDelay(50_000, 1)).isEqualTo(VcmpHandler.MAX_LISTENER_RETRY_DELAY_MS);
        // a configured zero/negative delay must not degenerate into a zero-backoff burst
        assertThat(VcmpHandler.computeRetryDelay(0, 3)).isEqualTo(4);
        assertThat(VcmpHandler.computeRetryDelay(-100, 1)).isEqualTo(1);
    }

    /**
     * A deliberate status must be recognized even when a synchronous listener rethrows it
     * wrapped in a CompletionException (e.g. from joining a failed future internally).
     */
    @Test
    void doesNotRetryWrappedDeliberateStatusOnSyncPath() throws Exception {
        Fixture fixture = new Fixture();

        fixture.receive("{\"@type\":\"retry:WrappedBadRequest\"}");

        await().until(() -> !fixture.sentFrames.isEmpty());
        assertThat(fixture.target.invocations.get()).isEqualTo(1);
        ProblemDetail problemDetail = fixture.assertSingleNak();
        assertThat(problemDetail.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problemDetail.getDetail()).isEqualTo("wrapped but deliberate");
    }

    @Test
    void retriesFailedCompletableFuture() throws Exception {
        Fixture fixture = new Fixture();
        fixture.target.failUntilAttempt = 1;

        fixture.receive("{\"@type\":\"retry:Future\"}");

        await().until(() -> !fixture.sentFrames.isEmpty());
        assertThat(fixture.target.invocations.get()).isEqualTo(2);
        assertThat(fixture.sentFrames).hasSize(1);
        assertThat(fixture.sentFrames.get(0)).startsWith("ACK" + MSG_ID);
    }

    @Test
    void singleAttemptDisablesRetry() throws Exception {
        Fixture fixture = new Fixture();
        fixture.handler.setListenerRetryAttempts(1);
        fixture.target.failUntilAttempt = Integer.MAX_VALUE;

        fixture.receive("{\"@type\":\"retry:Transient\"}");

        await().until(() -> !fixture.sentFrames.isEmpty());
        assertThat(fixture.target.invocations.get()).isEqualTo(1);
        fixture.assertSingleNak();
    }

    @Test
    void naksUnknownMessageImmediately() throws Exception {
        Fixture fixture = new Fixture();

        fixture.receive("{\"@type\":\"retry:Unknown\"}");

        await().until(() -> !fixture.sentFrames.isEmpty());
        assertThat(fixture.target.invocations.get()).isEqualTo(0);
        fixture.assertSingleNak();
    }

    /**
     * A handler wired to a mocked WebSocketSession, capturing every outbound frame.
     */
    private static class Fixture {
        final Target target = new Target();
        final VcmpHandler handler;
        final WebSocketSession session;
        final List<String> sentFrames = new CopyOnWriteArrayList<>();
        final AtomicBoolean open = new AtomicBoolean(true);
        volatile boolean failSends;

        Fixture() throws Exception {
            handler = new VcmpHandler(target);
            // Keep retries fast; semantics are unaffected.
            handler.setListenerRetryInitialDelayMs(10);

            session = mock(WebSocketSession.class);
            when(session.getId()).thenReturn("session-1");
            when(session.isOpen()).thenAnswer(invocation -> open.get());
            when(session.getTextMessageSizeLimit()).thenReturn(8192);
            doAnswer(invocation -> {
                if (failSends) {
                    // The container throws unchecked when the peer disconnects mid-send.
                    throw new IllegalStateException("connection lost while sending");
                }
                sentFrames.add(((TextMessage) invocation.getArgument(0)).getPayload());
                return null;
            }).when(session).sendMessage(any());

            handler.afterConnectionEstablished(session);
        }

        void receive(String messageJson) {
            handler.handleMessage(session, new TextMessage("MSG" + MSG_ID + messageJson));
        }

        ProblemDetail assertSingleNak() throws Exception {
            assertThat(sentFrames).hasSize(1);
            String frame = sentFrames.get(0);
            assertThat(frame).startsWith("NAK" + MSG_ID);
            return ObjectMapperHolder.createObjectMapper()
                    .readValue(frame.substring(("NAK" + MSG_ID).length()), ProblemDetail.class);
        }
    }

}
