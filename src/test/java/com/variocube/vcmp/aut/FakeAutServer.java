package com.variocube.vcmp.aut;

import lombok.SneakyThrows;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * A minimal VCMP server written by hand, to test the client rules independently of this library's server. Behaviour by
 * path ({@code /fake/<mode>?expiresIn=<ms>&delay=<ms>&push=1}):
 * <ul>
 *     <li>{@code silent}: never answers (like a server that does not know AUT);</li>
 *     <li>{@code ack}: ACKs every AUT, after {@code delay} ms, with {@code expiresIn} if given; with {@code push}, sends
 *     a MSG before the first ACK; ACKs every MSG;</li>
 *     <li>{@code nak}: NAKs the first AUT with 401 and closes (1008);</li>
 *     <li>{@code refresh-nak}: ACKs the first AUT with {@code expiresIn}, NAKs every later one with 401, and closes
 *     (1008) at the expiry;</li>
 *     <li>{@code refresh-silent}: ACKs the first AUT with {@code expiresIn}, never answers a later one, and closes
 *     (1008) at the expiry;</li>
 *     <li>{@code refresh-nak-once}: ACKs every AUT with {@code expiresIn}, except the second, which is NAKed with
 *     401.</li>
 * </ul>
 * With {@code malformed=array|negative|fraction|string}, an {@code ack} answers with such a malformed payload.
 * <ul>
 * </ul>
 */
@Configuration
public class FakeAutServer extends TextWebSocketHandler implements WebSocketConfigurer {

    public record Frame(String session, long at, String type, String id, String payload) {
    }

    public final List<Frame> frames = new CopyOnWriteArrayList<>();
    public final List<String> sessions = new CopyOnWriteArrayList<>();
    public final List<String> closed = new CopyOnWriteArrayList<>();

    /** The close code of each closed session (e.g. 4008 when the client closed it for authentication reasons). */
    public final java.util.Map<String, Integer> closeCodes = new java.util.concurrent.ConcurrentHashMap<>();
    public final java.util.Map<String, String> closeReasons = new java.util.concurrent.ConcurrentHashMap<>();

    /** When the fake server itself closed a session, per session. */
    public final java.util.Map<String, Long> closedByServerAt = new java.util.concurrent.ConcurrentHashMap<>();

    /** When the first ACK to an AUT was sent, per session. */
    public final java.util.Map<String, Long> firstAckAt = new java.util.concurrent.ConcurrentHashMap<>();

    static final java.util.Map<String, String> MALFORMED_ACKS = java.util.Map.of(
            "array", "[1]",
            "negative", "{\"expiresIn\":-5}",
            "fraction", "{\"expiresIn\":2.5}",
            "string", "{\"expiresIn\":\"9\"}");

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(this, "/fake/*");
    }

    public void clear() {
        frames.clear();
        sessions.clear();
        closed.clear();
        closeCodes.clear();
        closeReasons.clear();
        firstAckAt.clear();
        closedByServerAt.clear();
    }

    public List<Frame> framesOf(String type) {
        return frames.stream().filter(frame -> frame.type().equals(type)).toList();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closeCodes.put(session.getId(), status.getCode());
        if (status.getReason() != null) {
            closeReasons.put(session.getId(), status.getReason());
        }
        closed.add(session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String raw = message.getPayload();
        String type = raw.substring(0, 3);
        String id = type.equals("HBT") ? null : raw.substring(3, 15);
        String payload = type.equals("HBT") || raw.length() <= 15 ? null : raw.substring(15);
        frames.add(new Frame(session.getId(), System.currentTimeMillis(), type, id, payload));

        var query = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams();
        String mode = session.getUri().getPath().substring("/fake/".length());
        String expiresIn = query.getFirst("expiresIn");
        long delay = query.getFirst("delay") != null ? Long.parseLong(query.getFirst("delay")) : 0;
        boolean first = !firstAckAt.containsKey(session.getId());

        switch (type) {
            case "AUT" -> {
                switch (mode) {
                    case "silent" -> {
                    }
                    case "ack" -> {
                        if (first && query.getFirst("push") != null) {
                            send(session, "MSGpush00000001{\"@type\":\"aut:Hello\",\"text\":\"before ack\"}");
                        }
                        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> {
                            firstAckAt.putIfAbsent(session.getId(), System.currentTimeMillis());
                            String rawAck = MALFORMED_ACKS.get(String.valueOf(query.getFirst("malformed")));
                            send(session, "ACK" + id + (rawAck != null ? rawAck : expiresIn != null
                                    ? "{\"expiresIn\":" + expiresIn + ",\"who\":\"fake\"}"
                                    : ""));
                        });
                    }
                    case "nak" -> {
                        send(session, "NAK" + id + "{\"status\":401,\"title\":\"Authentication failed\"}");
                        close(session);
                    }
                    case "refresh-nak", "refresh-silent" -> {
                        if (first) {
                            firstAckAt.put(session.getId(), System.currentTimeMillis());
                            send(session, "ACK" + id + "{\"expiresIn\":" + expiresIn + "}");
                            CompletableFuture.delayedExecutor(Long.parseLong(expiresIn), TimeUnit.MILLISECONDS)
                                    .execute(() -> {
                                        closedByServerAt.put(session.getId(), System.currentTimeMillis());
                                        close(session);
                                    });
                        }
                        else if (mode.equals("refresh-nak")) {
                            send(session, "NAK" + id + "{\"status\":401,\"title\":\"Authentication failed\"}");
                        }
                    }
                    case "refresh-nak-once" -> {
                        long auts = frames.stream()
                                .filter(f -> f.session().equals(session.getId()) && f.type().equals("AUT"))
                                .count();
                        if (auts == 2) {
                            send(session, "NAK" + id + "{\"status\":401,\"title\":\"Authentication failed\"}");
                        }
                        else {
                            firstAckAt.putIfAbsent(session.getId(), System.currentTimeMillis());
                            send(session, "ACK" + id + "{\"expiresIn\":" + expiresIn + "}");
                        }
                    }
                    default -> throw new IllegalArgumentException(mode);
                }
            }
            case "MSG" -> send(session, "ACK" + id);
            default -> {
            }
        }
    }

    @SneakyThrows
    private static synchronized void send(WebSocketSession session, String frame) {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(frame));
        }
    }

    @SneakyThrows
    private static void close(WebSocketSession session) {
        if (session.isOpen()) {
            session.close(CloseStatus.POLICY_VIOLATION);
        }
    }
}
