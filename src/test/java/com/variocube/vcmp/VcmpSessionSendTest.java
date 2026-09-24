package com.variocube.vcmp;

import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.Value;
import lombok.val;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the marker semantics of {@link VcmpSession#send}'s failure branches — the core of the
 * {@link LocalConnectionProblem} scope: deterministic local failures must stay unmarked (consumers route them
 * to their give-up path), transient send failures on an open session must be marked (retry later).
 */
class VcmpSessionSendTest {

    @Value
    @JsonTypeName("test:SerializableMessage")
    static class SerializableMessage implements VcmpMessage {
        String foo;
    }

    @JsonTypeName("test:UnserializableMessage")
    static class UnserializableMessage implements VcmpMessage {
        public String getBoom() {
            throw new IllegalStateException("boom");
        }
    }

    @AfterEach
    void clearInterruptFlag() {
        // the interrupted-send test leaves the flag set (sendFrame restores it before throwing)
        Thread.interrupted();
    }

    @Test
    void serializationFailureNakIsUnmarked() {
        val session = new VcmpSession(mock(WebSocketSession.class), new VcmpHandler(new Object()));

        val nak = new AtomicReference<ProblemDetail>();
        session.send(new UnserializableMessage()).peekNak(nak::set);

        // deterministic local failure: retrying cannot succeed, so it must NOT classify as transport
        assertThat(nak.get()).isNotNull();
        assertThat(nak.get().getStatus()).isEqualTo(500);
        assertThat(LocalConnectionProblem.isMarked(nak.get())).isFalse();
    }

    @Test
    void interruptedSendOnOpenSessionIsMarkedSendFailure() {
        val webSocketSession = mock(WebSocketSession.class);
        when(webSocketSession.isOpen()).thenReturn(true);
        when(webSocketSession.getTextMessageSizeLimit()).thenReturn(8192);
        val session = new VcmpSession(webSocketSession, new VcmpHandler(new Object()));

        // a pre-set interrupt makes the send-lock acquisition throw immediately — the fast way to reach
        // the transient-IOException branch (chunk-send failures are rethrown as SessionClosedException)
        Thread.currentThread().interrupt();
        val nak = new AtomicReference<ProblemDetail>();
        session.send(new SerializableMessage("foo")).peekNak(nak::set);

        assertThat(nak.get()).isNotNull();
        assertThat(nak.get().getStatus()).isEqualTo(500);
        assertThat(nak.get().getTitle()).isEqualTo("Send failed");
        assertThat(LocalConnectionProblem.isMarked(nak.get())).isTrue();
    }

    @Test
    void runtimeExceptionWhileOpenIsMarkedSendFailure() {
        val webSocketSession = mock(WebSocketSession.class);
        when(webSocketSession.isOpen()).thenReturn(true);
        when(webSocketSession.getTextMessageSizeLimit()).thenThrow(new IllegalStateException("container hiccup"));
        val session = new VcmpSession(webSocketSession, new VcmpHandler(new Object()));

        val nak = new AtomicReference<ProblemDetail>();
        session.send(new SerializableMessage("foo")).peekNak(nak::set);

        assertThat(nak.get()).isNotNull();
        assertThat(nak.get().getStatus()).isEqualTo(500);
        assertThat(nak.get().getTitle()).isEqualTo("Send failed");
        assertThat(LocalConnectionProblem.isMarked(nak.get())).isTrue();
    }
}
