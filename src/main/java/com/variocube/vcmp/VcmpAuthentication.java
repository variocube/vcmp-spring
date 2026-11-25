package com.variocube.vcmp;

import java.security.Principal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The outcome of a successful authentication of a VCMP session, returned by a {@link VcmpAuthenticator} or
 * {@link VcmpHandshakeAuthenticator} method.
 * <p>
 * The principal is what the session is authenticated as; it never changes for the lifetime of the session (a
 * re-authentication must yield an equal principal). The optional expiry is the instant at which the server closes the
 * session unless a re-authentication has moved it; it is sent to the client as {@code expiresIn} in the {@code ACK}.
 * The optional details are endpoint-defined members of the {@code ACK} payload, passed to the client application.
 */
public final class VcmpAuthentication {

    /** The member of the {@code ACK} payload reserved by the protocol. */
    public static final String EXPIRES_IN = "expiresIn";

    private final Principal principal;
    private final Instant expiresAt;
    private final Map<String, Object> details;

    private VcmpAuthentication(Principal principal, Instant expiresAt, Map<String, Object> details) {
        this.principal = Objects.requireNonNull(principal, "principal");
        this.expiresAt = expiresAt;
        this.details = details;
    }

    /** An authentication that does not expire. */
    public static VcmpAuthentication of(Principal principal) {
        return new VcmpAuthentication(principal, null, Map.of());
    }

    /**
     * An authentication that expires at the given instant (e.g. a JWT's {@code exp}); {@code null} means it does not
     * expire.
     */
    public static VcmpAuthentication of(Principal principal, Instant expiresAt) {
        return new VcmpAuthentication(principal, expiresAt, Map.of());
    }

    /**
     * Returns a copy carrying the given endpoint-defined members of the {@code ACK} payload, e.g. what the session is
     * authenticated as. They must not contain {@value #EXPIRES_IN}, which is reserved for the protocol.
     */
    public VcmpAuthentication withDetails(Map<String, ?> details) {
        if (details.containsKey(EXPIRES_IN)) {
            throw new IllegalArgumentException("`" + EXPIRES_IN + "` is reserved for the protocol.");
        }
        return new VcmpAuthentication(principal, expiresAt, Collections.unmodifiableMap(new LinkedHashMap<>(details)));
    }

    public Principal getPrincipal() {
        return principal;
    }

    /** The instant the authentication expires, or {@code null} if it does not. */
    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** The endpoint-defined members of the {@code ACK} payload; empty if there are none. */
    public Map<String, Object> getDetails() {
        return details;
    }

    @Override
    public String toString() {
        // Deliberately without the principal: its name may be an identifier that should not end up in logs.
        return "VcmpAuthentication(expiresAt=" + expiresAt + ")";
    }
}
