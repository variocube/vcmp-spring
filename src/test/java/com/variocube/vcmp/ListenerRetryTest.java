package com.variocube.vcmp;

import com.fasterxml.jackson.annotation.JsonTypeName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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
        assertThat(problemDetail.getDetail()).isEqualTo("deliberately not found");
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

        Fixture() throws Exception {
            handler = new VcmpHandler(target);
            // Keep retries fast; semantics are unaffected.
            handler.setListenerRetryInitialDelayMs(10);

            session = mock(WebSocketSession.class);
            when(session.getId()).thenReturn("session-1");
            when(session.isOpen()).thenReturn(true);
            when(session.getTextMessageSizeLimit()).thenReturn(8192);
            doAnswer(invocation -> {
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
