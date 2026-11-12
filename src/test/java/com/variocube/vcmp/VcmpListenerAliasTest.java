package com.variocube.vcmp;

import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VcmpListenerAliasTest {

    /** The id Jackson derives for the nested {@link Utilization}: no @JsonTypeName, so the unqualified class name. */
    private static final String PRIMARY_ID = "VcmpListenerAliasTest$Utilization";

    /** Like cube-common's CubeUtilization: no @JsonTypeName. */
    static class Utilization implements VcmpMessage {
    }

    @JsonTypeName("test:Other")
    static class Other implements VcmpMessage {
    }

    static class AliasTarget {
        final List<Object> received = new CopyOnWriteArrayList<>();
        final CompletableFuture<VcmpSession> session = new CompletableFuture<>();

        @VcmpSessionConnected
        public void connected(VcmpSession session) {
            this.session.complete(session);
        }

        @VcmpListener(aliases = "cube:Utilization")
        public void handle(Utilization message) {
            received.add(message);
        }
    }

    static class CollidingWithOtherListener {
        @VcmpListener(aliases = "test:Other")
        public void handle(Utilization message) {
        }

        @VcmpListener
        public void other(Other message) {
        }
    }

    static class CollidingWithPrimaryId {
        @VcmpListener(aliases = "VcmpListenerAliasTest$Utilization")
        public void handle(Utilization message) {
        }
    }

    static class CollidingAliases {
        @VcmpListener(aliases = "test:Same")
        public void handle(Utilization message) {
        }

        @VcmpListener(aliases = "test:Same")
        public void other(Other message) {
        }
    }

    static class BlankAlias {
        @VcmpListener(aliases = " ")
        public void handle(Utilization message) {
        }
    }

    private static WebSocketSession mockSession(String id) {
        val webSocketSession = mock(WebSocketSession.class);
        when(webSocketSession.getId()).thenReturn(id);
        when(webSocketSession.isOpen()).thenReturn(true);
        when(webSocketSession.getTextMessageSizeLimit()).thenReturn(8192);
        return webSocketSession;
    }

    @Test
    void primaryIdAndAliasReachTheSameListenerAndAreAcked() throws Exception {
        val target = new AliasTarget();
        val handler = new VcmpHandler(target);
        val webSocketSession = mockSession("alias-in");
        handler.afterConnectionEstablished(webSocketSession);

        handler.handleMessage(webSocketSession,
                new TextMessage(VcmpFrame.createMessage("{\"@type\":\"VcmpListenerAliasTest$Utilization\"}").serialize()));
        handler.handleMessage(webSocketSession,
                new TextMessage(VcmpFrame.createMessage("{\"@type\":\"cube:Utilization\"}").serialize()));

        val captor = ArgumentCaptor.forClass(TextMessage.class);
        await().untilAsserted(() -> {
            verify(webSocketSession, atLeast(2)).sendMessage(captor.capture());
            assertThat(captor.getAllValues()).extracting(TextMessage::getPayload)
                    .filteredOn(frame -> frame.startsWith("ACK")).hasSize(2);
        });
        assertThat(target.received).hasSize(2).allMatch(Utilization.class::isInstance);
    }

    @Test
    void outgoingFramesKeepThePrimaryId() throws Exception {
        val target = new AliasTarget();
        val handler = new VcmpHandler(target);
        val webSocketSession = mockSession("alias-out");
        handler.afterConnectionEstablished(webSocketSession);
        val session = target.session.get(5, TimeUnit.SECONDS);

        session.send(new Utilization());

        val captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(webSocketSession).sendMessage(captor.capture());
        assertThat(captor.getValue().getPayload()).contains("\"@type\":\"" + PRIMARY_ID + "\"")
                .doesNotContain("cube:Utilization");
    }

    @Test
    void collidingOrBlankAliasesFailAtConstruction() {
        assertThatThrownBy(() -> new VcmpHandler(new CollidingWithOtherListener()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("test:Other");
        assertThatThrownBy(() -> new VcmpHandler(new CollidingWithPrimaryId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("VcmpListenerAliasTest$Utilization");
        assertThatThrownBy(() -> new VcmpHandler(new CollidingAliases()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("test:Same");
        assertThatThrownBy(() -> new VcmpHandler(new BlankAlias()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Blank");
    }
}
