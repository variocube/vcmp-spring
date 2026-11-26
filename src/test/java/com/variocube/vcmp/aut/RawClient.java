package com.variocube.vcmp.aut;

import lombok.SneakyThrows;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.Closeable;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A plain WebSocket client that sends VCMP frames by hand, so the server rules are tested independently of this
 * library's own client.
 */
public class RawClient extends TextWebSocketHandler implements Closeable {

    public final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    public final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
    private final WebSocketSession session;

    @SneakyThrows
    public RawClient(String url) {
        session = new StandardWebSocketClient().execute(this, url).get(5, TimeUnit.SECONDS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        received.add(message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closed.complete(status);
    }

    @SneakyThrows
    public void send(String frame) {
        session.sendMessage(new TextMessage(frame));
    }

    @SneakyThrows
    public void sendBinary(byte[] bytes) {
        session.sendMessage(new org.springframework.web.socket.BinaryMessage(bytes));
    }

    /** Sends an AUT with the given token under the given 12-character id. */
    public void aut(String id, String token) {
        send("AUT" + id + "{\"token\":\"" + token + "\"}");
    }

    public void hello(String id, String text) {
        send("MSG" + id + "{\"@type\":\"aut:Hello\",\"text\":\"" + text + "\"}");
    }

    /** The next frame that is not a heartbeat. */
    @SneakyThrows
    public String next() {
        String frame = received.poll(3, TimeUnit.SECONDS);
        assertThat(frame).as("frame received").isNotNull();
        return frame;
    }

    @SneakyThrows
    public String nextWithin(long millis) {
        return received.poll(millis, TimeUnit.MILLISECONDS);
    }

    @SneakyThrows
    public CloseStatus awaitClose() {
        return closed.get(3, TimeUnit.SECONDS);
    }

    public boolean isOpen() {
        return session.isOpen();
    }

    @Override
    @SneakyThrows
    public void close() {
        if (session.isOpen()) {
            session.close();
        }
    }
}
