package com.variocube.vcmp;

import com.variocube.vcmp.aut.TokenCredential;
import com.variocube.vcmp.aut.UserPrincipal;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Server rules of the {@link VcmpHandler} that need control over the WebSocket session (mocked). */
class AutHandlerTest {

    public static class SlowEndpoint {
        final List<String> authenticated = new CopyOnWriteArrayList<>();

        @VcmpAuthenticator
        public VcmpAuthentication authenticate(TokenCredential credential) throws InterruptedException {
            authenticated.add(credential.token());
            Thread.sleep(1500);
            return VcmpAuthentication.of(new UserPrincipal(credential.token()));
        }
    }

    /**
     * In a connect storm, a first AUT may wait for the connect throttle beyond the auth timeout: the session is gone
     * by then, and its authenticator must not run (DB work for nothing, holding a permit others need).
     */
    @Test
    void authenticatorDoesNotRunForSessionsThatTimedOutWhileQueued() throws Exception {
        val endpoint = new SlowEndpoint();
        val handler = new VcmpHandler(endpoint);
        handler.setConnectThrottle(new Semaphore(1));
        handler.requireAuthentication(Duration.ofMillis(1000));

        val first = webSocketSession("first");
        val second = webSocketSession("second");
        handler.afterConnectionEstablished(first.session);
        handler.afterConnectionEstablished(second.session);
        handler.handleMessage(first.session, new TextMessage("AUTaut000000001{\"token\":\"first\"}"));
        await().until(() -> endpoint.authenticated.contains("first"));
        // Queued behind the first (1.5 s) and timed out (1 s) before it gets the permit.
        handler.handleMessage(second.session, new TextMessage("AUTaut000000002{\"token\":\"second\"}"));

        await().atMost(Duration.ofSeconds(3)).until(() -> !second.open.get());
        Thread.sleep(1200); // the first has released the permit by now
        assertThat(endpoint.authenticated).containsExactly("first");
    }

    public static class FastEndpoint {
        final List<String> hellos = new CopyOnWriteArrayList<>();

        @VcmpAuthenticator
        public VcmpAuthentication authenticate(TokenCredential credential) {
            return VcmpAuthentication.of(new UserPrincipal(credential.token()));
        }

        @VcmpListener
        public void handleHello(com.variocube.vcmp.aut.Hello hello) {
            hellos.add(hello.getText());
        }
    }

    /**
     * The commit follows the ACK send. A MSG the client sends as soon as it has the ACK may reach the receiving thread
     * before the commit: the gate must wait for it rather than close the session as unauthenticated.
     */
    @Test
    void messageRightAfterTheAckWaitsForTheCommit() throws Exception {
        val endpoint = new FastEndpoint();
        val handler = new VcmpHandler(endpoint);
        handler.requireAuthentication(Duration.ofSeconds(5));
        val mocked = webSocketSession("s");
        val ackSending = new java.util.concurrent.CountDownLatch(1);
        val releaseAck = new java.util.concurrent.CountDownLatch(1);
        doAnswer(invocation -> {
            TextMessage message = invocation.getArgument(0);
            if (message.getPayload().startsWith("ACKaut")) {
                ackSending.countDown();
                releaseAck.await();
            }
            return null;
        }).when(mocked.session).sendMessage(any());

        handler.afterConnectionEstablished(mocked.session);
        handler.handleMessage(mocked.session, new TextMessage("AUTaut000000001{\"token\":\"ann\"}"));
        assertThat(ackSending.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // The ACK is on the wire; the client answers with a MSG while the commit has not happened yet.
        val receiver = new Thread(() -> handler.handleMessage(mocked.session,
                new TextMessage("MSGmsg000000001{\"@type\":\"aut:Hello\",\"text\":\"right away\"}")));
        receiver.start();
        Thread.sleep(200);
        releaseAck.countDown();
        receiver.join(2000);

        await().until(() -> endpoint.hellos.contains("right away"));
        assertThat(mocked.open.get()).isTrue();
    }

    public static class FixedExpiryEndpoint {
        static final java.time.Instant EXPIRES_AT = java.time.Instant.parse("2030-01-01T00:00:00Z");

        @VcmpAuthenticator
        public VcmpAuthentication authenticate(TokenCredential credential) {
            return VcmpAuthentication.of(new UserPrincipal("ann"),
                    credential.token().equals("fixed") ? EXPIRES_AT : null);
        }
    }

    /** A clock that returns the given instants one per call, then the last one forever. */
    static java.time.Clock steppingClock(java.time.Instant... instants) {
        val queue = new java.util.concurrent.ConcurrentLinkedQueue<>(List.of(instants));
        val last = instants[instants.length - 1];
        return new java.time.Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public java.time.Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public java.time.Instant instant() {
                val next = queue.poll();
                return next != null ? next : last;
            }
        };
    }

    /** The expiry passes between the check and building the ACK: NAK 401 rather than an ACK with expiresIn 0. */
    @Test
    void credentialExpiringBeforeTheAckOfTheFirstAutIsNakked401AndClosed() throws Exception {
        val handler = new VcmpHandler(new FixedExpiryEndpoint());
        handler.requireAuthentication(Duration.ofSeconds(5));
        val expiresAt = FixedExpiryEndpoint.EXPIRES_AT;
        handler.getServerAuthenticator().setClock(steppingClock(expiresAt.minusMillis(1), expiresAt));
        val mocked = webSocketSession("s");
        val sent = recordSentFrames(mocked);

        handler.afterConnectionEstablished(mocked.session);
        handler.handleMessage(mocked.session, new TextMessage("AUTaut000000001{\"token\":\"fixed\"}"));

        await().until(() -> !mocked.open.get());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).startsWith("NAKaut000000001").contains("\"status\":401");
    }

    @Test
    void credentialExpiringBeforeTheAckOfARefreshIsNakked401AndKeepsTheSession() throws Exception {
        val handler = new VcmpHandler(new FixedExpiryEndpoint());
        handler.requireAuthentication(Duration.ofSeconds(5));
        val mocked = webSocketSession("s");
        val sent = recordSentFrames(mocked);
        handler.afterConnectionEstablished(mocked.session);
        handler.handleMessage(mocked.session, new TextMessage("AUTaut000000001{\"token\":\"open\"}"));
        await().until(() -> sent.size() == 1);
        assertThat(sent.get(0)).isEqualTo("ACKaut000000001");

        val expiresAt = FixedExpiryEndpoint.EXPIRES_AT;
        handler.getServerAuthenticator().setClock(steppingClock(expiresAt.minusMillis(1), expiresAt));
        handler.handleMessage(mocked.session, new TextMessage("AUTaut000000002{\"token\":\"fixed\"}"));

        await().until(() -> sent.size() == 2);
        assertThat(sent.get(1)).startsWith("NAKaut000000002").contains("\"status\":401");
        assertThat(mocked.open.get()).isTrue();
    }

    private static List<String> recordSentFrames(Mocked mocked) throws Exception {
        val sent = new CopyOnWriteArrayList<String>();
        doAnswer(invocation -> {
            sent.add(((TextMessage) invocation.getArgument(0)).getPayload());
            return null;
        }).when(mocked.session).sendMessage(any());
        return sent;
    }

    record Mocked(WebSocketSession session, AtomicBoolean open) {
    }

    private static Mocked webSocketSession(String id) throws Exception {
        val open = new AtomicBoolean(true);
        val session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenAnswer(invocation -> open.get());
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.getHandshakeHeaders()).thenReturn(new HttpHeaders());
        when(session.getUri()).thenReturn(URI.create("ws://localhost/aut"));
        when(session.getTextMessageSizeLimit()).thenReturn(8192);
        doAnswer(invocation -> {
            open.set(false);
            return null;
        }).when(session).close(any(CloseStatus.class));
        return new Mocked(session, open);
    }
}
