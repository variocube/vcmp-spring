package com.variocube.vcmp.client;
import org.springframework.http.HttpHeaders;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;

import lombok.val;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the asynchronous handshake-completion guard added for issue #15 / PR #14.
 *
 * <p>The handshake completes asynchronously, so {@code stop()} may run while {@code webSocketSession}
 * is still null. These tests drive that window deterministically by injecting a mock client whose
 * handshake future we complete by hand, after {@code stop()} has already run.
 */
class VcmpConnectionManagerTest {

    /**
     * Reproduces the leak window: the handshake completes <em>after</em> {@code stop()}. The guard in
     * {@code openSession()} must close the freshly-established session instead of publishing it.
     */
    @Test
    void closesSessionEstablishedAfterStop() throws Exception {
        val manager = new VcmpConnectionManager(new Object(), "ws://localhost/test");
        val handshake = new CompletableFuture<WebSocketSession>();
        injectClient(manager, handshake);

        manager.start(); // kicks off the handshake; the future stays pending
        manager.stop();  // isRunning = false; closeSession() finds webSocketSession still null

        val session = mock(WebSocketSession.class);
        handshake.complete(session); // handshake lands a moment too late

        // The session established after stop() must be closed, not leaked or published.
        verify(session).close();
        assertThat(sessionField(manager)).isNull();
    }

    /**
     * Sanity check the happy path is untouched: a handshake that completes while still running
     * publishes the session and does not close it.
     */
    @Test
    void publishesSessionEstablishedWhileRunning() throws Exception {
        val manager = new VcmpConnectionManager(new Object(), "ws://localhost/test");
        val handshake = new CompletableFuture<WebSocketSession>();
        injectClient(manager, handshake);

        manager.start();
        val session = mock(WebSocketSession.class);
        handshake.complete(session);

        assertThat(sessionField(manager)).isSameAs(session);
        verify(session, never()).close();

        manager.stop();
    }

    @Test
    void refreshesHeadersAfterARejectedHandshake() throws Exception {
        val target = new HeadersTarget();
        val manager = new VcmpConnectionManager(target, "ws://localhost/test");
        val requests = new CopyOnWriteArrayList<WebSocketHttpHeaders>();
        val client = mock(StandardWebSocketClient.class);
        val session = mock(WebSocketSession.class);
        when(client.execute(any(WebSocketHandler.class), any(WebSocketHttpHeaders.class), any(URI.class)))
                .thenAnswer(invocation -> {
                    requests.add(invocation.getArgument(1));
                    return requests.size() == 1 ? CompletableFuture.failedFuture(new IOException("Rejected"))
                            : CompletableFuture.completedFuture(session);
                });
        setField(manager, "webSocketClient", client);
        manager.setReconnectTimeoutMin(Duration.ZERO);
        manager.setReconnectTimeoutMax(Duration.ofMillis(1));
        assertThat(target.calls.get()).isZero();
        try {
            manager.start();
            await().untilAsserted(() -> assertThat(requests).hasSize(2));
            assertThat(requests.get(0).getFirst("Authorization")).isEqualTo("Bearer token-1");
            assertThat(requests.get(1).getFirst("Authorization")).isEqualTo("Bearer token-2");
            assertThat(requests.get(0).getFirst("X-First-Only")).isEqualTo("yes");
            assertThat(requests.get(1).getFirst("X-First-Only")).isNull();
        } finally {
            manager.stop();
        }
    }

    @Test
    void retriesHeaderFailuresWithoutSendingAnUnauthenticatedHandshake() throws Exception {
        val target = new HeadersTarget();
        target.failFirst = true;
        val manager = new VcmpConnectionManager(target, "ws://localhost/test");
        val requests = new CopyOnWriteArrayList<WebSocketHttpHeaders>();
        val client = mock(StandardWebSocketClient.class);
        when(client.execute(any(WebSocketHandler.class), any(WebSocketHttpHeaders.class), any(URI.class)))
                .thenAnswer(invocation -> {
                    requests.add(invocation.getArgument(1));
                    return CompletableFuture.completedFuture(mock(WebSocketSession.class));
                });
        setField(manager, "webSocketClient", client);
        manager.setReconnectTimeoutMin(Duration.ZERO);
        manager.setReconnectTimeoutMax(Duration.ofMillis(1));
        try {
            manager.start();
            await().untilAsserted(() -> assertThat(requests).hasSize(1));
            assertThat(target.calls.get()).isEqualTo(2);
            assertThat(requests.get(0).getFirst("Authorization")).isEqualTo("Bearer token-2");
        } finally {
            manager.stop();
        }
    }

    public static class HeadersTarget {
        final AtomicInteger calls = new AtomicInteger();
        boolean failFirst;

        @VcmpHttpHeaders
        public void headers(HttpHeaders headers) {
            int attempt = calls.incrementAndGet();
            if (failFirst && attempt == 1) throw new IllegalStateException("Credential provider unavailable");
            headers.setBearerAuth("token-" + attempt);
            if (attempt == 1) headers.set("X-First-Only", "yes");
        }
    }

    private static void injectClient(VcmpConnectionManager manager, CompletableFuture<WebSocketSession> handshake)
            throws Exception {
        val client = mock(StandardWebSocketClient.class);
        when(client.execute(any(WebSocketHandler.class), any(WebSocketHttpHeaders.class), any(URI.class)))
                .thenReturn(handshake);
        setField(manager, "webSocketClient", client);
    }

    private static WebSocketSession sessionField(VcmpConnectionManager manager) throws Exception {
        val field = VcmpConnectionManager.class.getDeclaredField("webSocketSession");
        field.setAccessible(true);
        return (WebSocketSession) field.get(manager);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        val field = VcmpConnectionManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
