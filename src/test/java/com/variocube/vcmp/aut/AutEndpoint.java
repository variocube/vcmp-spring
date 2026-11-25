package com.variocube.vcmp.aut;

import com.variocube.vcmp.VcmpAuthentication;
import com.variocube.vcmp.VcmpAuthenticator;
import com.variocube.vcmp.VcmpListener;
import com.variocube.vcmp.VcmpSession;
import com.variocube.vcmp.VcmpSessionConnected;
import com.variocube.vcmp.VcmpSessionDisconnected;
import com.variocube.vcmp.server.VcmpEndpoint;
import com.variocube.vcmp.server.VcmpSessionPool;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An endpoint that requires session authentication. Tokens:
 * <ul>
 *     <li>{@code <user>}: authenticated as {@code <user>}, no expiry;</li>
 *     <li>{@code <user>@<millis>}: authenticated as {@code <user>}, expiring in {@code <millis>};</li>
 *     <li>{@code slow:<token>}: like {@code <token>}, after 300 ms;</li>
 *     <li>{@code unserializable:<token>}: like {@code <token>}, with ACK details that can't be serialized;</li>
 *     <li>{@code boom...}: a principal whose {@code equals} throws;</li>
 *     <li>{@code forbidden}: 403; {@code crash}: an unexpected exception; {@code nullprincipal}: no principal;
 *     anything starting with {@code bad}: invalid.</li>
 * </ul>
 */
@VcmpEndpoint(path = AutEndpoint.PATH, authenticated = true, authTimeoutMillis = AutEndpoint.AUTH_TIMEOUT_MILLIS)
public class AutEndpoint {

    public static final String PATH = "/aut";
    public static final long AUTH_TIMEOUT_MILLIS = 700;

    public final VcmpSessionPool sessionPool = new VcmpSessionPool();

    /** Every session the authenticator has seen, authenticated or not: an application must not be able to push to it. */
    public final VcmpSessionPool seenByAuthenticator = new VcmpSessionPool();
    public final List<VcmpSession> seenSessions = new CopyOnWriteArrayList<>();

    /** The principal names of the sessions reported connected / disconnected. */
    public final List<String> connected = new CopyOnWriteArrayList<>();
    public final List<String> disconnected = new CopyOnWriteArrayList<>();
    public final List<String> hellos = new CopyOnWriteArrayList<>();
    public final AtomicInteger authentications = new AtomicInteger();

    @VcmpAuthenticator
    public VcmpAuthentication authenticate(VcmpSession session, TokenCredential credential) throws InterruptedException {
        authentications.incrementAndGet();
        seenByAuthenticator.add(session);
        seenSessions.add(session);
        String token = credential.token();
        if (token == null || token.startsWith("bad")) {
            return null;
        }
        if (token.startsWith("slow:")) {
            Thread.sleep(300);
            token = token.substring("slow:".length());
        }
        if (token.equals("forbidden")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed");
        }
        if (token.equals("crash")) {
            throw new IllegalStateException("Database down");
        }
        if (token.equals("nullprincipal")) {
            return VcmpAuthentication.of(null);
        }
        boolean unserializable = token.startsWith("unserializable:");
        if (unserializable) {
            token = token.substring("unserializable:".length());
        }
        String[] parts = token.split("@");
        Instant expiresAt = parts.length > 1 ? Instant.now().plusMillis(Long.parseLong(parts[1])) : null;
        Principal principal = parts[0].startsWith("boom") ? new ExplodingPrincipal(parts[0]) : new UserPrincipal(parts[0]);
        return VcmpAuthentication.of(principal, expiresAt)
                .withDetails(Map.of("user", unserializable ? new Unserializable() : parts[0]));
    }

    /** A principal whose comparison fails. */
    public record ExplodingPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean equals(Object other) {
            throw new IllegalStateException("Cannot compare");
        }

        @Override
        public int hashCode() {
            return name.hashCode();
        }
    }

    public static class Unserializable {
        public String getValue() {
            throw new IllegalStateException("Cannot serialize");
        }
    }

    @VcmpSessionConnected
    public void handleConnected(VcmpSession session) {
        connected.add(nameOf(session));
        sessionPool.add(session);
    }

    @VcmpSessionDisconnected
    public void handleDisconnected(VcmpSession session) {
        disconnected.add(nameOf(session));
        sessionPool.remove(session);
    }

    private static String nameOf(VcmpSession session) {
        return session.getPrincipal() != null ? session.getPrincipal().getName() : "<anonymous>";
    }

    @VcmpListener
    public String handleHello(Hello hello, String username) {
        hellos.add(username + ":" + hello.getText());
        return "hello " + username;
    }
}
