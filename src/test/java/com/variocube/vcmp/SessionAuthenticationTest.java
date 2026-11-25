package com.variocube.vcmp;

import com.variocube.vcmp.aut.UserPrincipal;
import lombok.val;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** The server commits an authentication only once its ACK is out (#34, "Authenticated only once the ACK is out"). */
@SuppressWarnings("unchecked")
class SessionAuthenticationTest {

    @Test
    void firstAuthenticationIsNotCommittedWhenTheAckFails() {
        val state = new SessionAuthentication();
        state.require();
        state.onAutReceived();

        assertThatThrownBy(() -> state.authenticate(new UserPrincipal("ann"), null, Map.of(), () -> {
            // The ACK is being sent: nothing is committed yet.
            assertThat(state.isAuthenticated()).isFalse();
            throw new IOException("send failed");
        })).isInstanceOf(IOException.class);

        assertThat(state.isAuthenticated()).isFalse();
        assertThat(state.admitsMessages()).isFalse();
        assertThat(state.getPrincipal()).isNull();
        assertThat(state.isConnected()).isFalse();
        // The auth timeout still applies.
        assertThat(state.timeout()).isTrue();
    }

    @Test
    void firstAuthenticationIsCommittedAfterTheAck() throws IOException {
        val state = new SessionAuthentication();
        state.require();
        state.onAutReceived();

        assertThat(state.authenticate(new UserPrincipal("ann"), null, Map.of(), () -> {
        })).isTrue();
        assertThat(state.isAuthenticated()).isTrue();
        assertThat(state.isConnected()).isTrue();
    }

    @Test
    void reauthenticationKeepsThePreviousExpiryWhenTheAckFails() throws IOException {
        val state = new SessionAuthentication();
        state.require();
        state.onAutReceived();
        val expiresAt = Instant.now().plusSeconds(60);
        state.authenticate(new UserPrincipal("ann"), expiresAt, Map.of());
        state.onAutReceived();

        assertThatThrownBy(() -> state.reauthenticate(Instant.now().plusSeconds(600), Map.of(), () -> {
            assertThat(state.getExpiresAt()).isEqualTo(expiresAt);
            throw new IOException("send failed");
        })).isInstanceOf(IOException.class);

        assertThat(state.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(state.isAuthenticated()).isTrue();
    }

    /**
     * A commit holds the monitor while it sends the ACK, which may block on a stalled peer. Readers must not wait for
     * it: a session pool scans every session (head-of-line blocking for unrelated recipients), and a disconnect hook
     * reads the principal on a thread that may hold the very send lock the commit waits for.
     */
    @Test
    void readsDoNotWaitForAnAckBeingSent() throws Exception {
        val state = new SessionAuthentication();
        state.require();
        state.onAutReceived();
        state.authenticate(new UserPrincipal("ann"), Instant.now().plusSeconds(60), Map.of());
        state.onAutReceived();
        try (val blocked = blockInAckSend(() -> state.reauthenticate(Instant.now().plusSeconds(600), Map.of(),
                blockedAck()))) {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                assertThat(state.isAuthenticated()).isTrue();
                assertThat(state.isPending()).isFalse();
                assertThat(state.admitsMessages()).isTrue();
                assertThat(state.getPrincipal()).isEqualTo(new UserPrincipal("ann"));
                assertThat(state.getExpiresAt()).isNotNull();
                assertThat(state.getDetails()).isEmpty();
                assertThat(state.isConnected()).isTrue();
            });
        }
    }

    @Test
    void poolAndDisconnectHookDoNotWaitForAnotherThreadsAckSend() throws Exception {
        val disconnected = new java.util.concurrent.atomic.AtomicReference<String>();
        val target = new Object() {
            @VcmpSessionDisconnected
            public void disconnected(VcmpSession session) {
                disconnected.set(session.getUsername());
            }
        };
        val handler = new VcmpHandler(target);
        val webSocketSession = org.mockito.Mockito.mock(org.springframework.web.socket.WebSocketSession.class);
        org.mockito.Mockito.when(webSocketSession.getId()).thenReturn("x");
        org.mockito.Mockito.when(webSocketSession.isOpen()).thenReturn(true);
        val session = new VcmpSession(webSocketSession, handler);
        val state = session.getAuthentication();
        state.require();
        state.onAutReceived();
        state.authenticate(new UserPrincipal("ann"), null, Map.of());
        state.onAutReceived();
        val pool = new com.variocube.vcmp.server.VcmpSessionPool();
        pool.add(session);

        try (val blocked = blockInAckSend(() -> state.reauthenticate(null, Map.of(), blockedAck()))) {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                assertThat(pool.getOpenSessions()).containsExactly(session);
                assertThat(pool.hasSession("ann")).isTrue();
                assertThat(session.getUsername()).isEqualTo("ann");
            });
            // The disconnect hook, which reads the principal, runs while the ACK is still being sent.
            val sessions = VcmpHandler.class.getDeclaredField("sessions");
            sessions.setAccessible(true);
            ((Map<String, VcmpSession>) sessions.get(handler)).put("x", session);
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> handler.afterConnectionClosed(webSocketSession,
                    org.springframework.web.socket.CloseStatus.GOING_AWAY));
            assertThat(disconnected.get()).isEqualTo("ann");
        }
    }

    /** An ACK sender that blocks until the test releases it (a send lock held by a stalled push). */
    private final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    private final java.util.concurrent.CountDownLatch sending = new java.util.concurrent.CountDownLatch(1);

    private SessionAuthentication.AckSender blockedAck() {
        return () -> {
            sending.countDown();
            try {
                release.await();
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }

    interface Commit {
        void run() throws Exception;
    }

    /** Runs the commit on another thread until it is blocked in its ACK send; closing releases it. */
    private AutoCloseable blockInAckSend(Commit commit) throws InterruptedException {
        val thread = new Thread(() -> {
            try {
                commit.run();
            }
            catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        thread.start();
        assertThat(sending.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        return () -> {
            release.countDown();
            thread.join(2000);
        };
    }
}
