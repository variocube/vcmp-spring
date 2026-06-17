package com.variocube.vcmp.client;

import lombok.val;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
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
