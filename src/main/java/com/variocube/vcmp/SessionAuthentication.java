package com.variocube.vcmp;

import java.io.IOException;
import java.security.Principal;
import java.time.Instant;
import java.util.Map;

/**
 * The authentication state of one {@link VcmpSession}. All transitions are atomic (synchronized), so the frame
 * receiver, the authenticator, the auth timeout and the expiry check can race without a session ever being
 * half-authenticated.
 * <p>
 * Reads are lock-free (volatile fields written by the transitions): a transition may hold the monitor while it sends
 * an ACK, which can block on the session's send lock for a long time (a stalled peer). Nothing but the receiving
 * thread's gate ({@link #admitsMessagesAfterCommit()}) may wait for that: not a session pool scanning all its sessions,
 * not a disconnect hook reading the principal.
 */
final class SessionAuthentication {

    enum State {
        /** The session does not use VCMP authentication (an endpoint or client without the opt-in). */
        NONE,
        /** Waiting for the first {@code AUT} (server) or its answer (client). */
        PENDING,
        /** Server: the first {@code AUT} is being authenticated. */
        AUTHENTICATING,
        AUTHENTICATED,
        /** Closed for a policy violation; nothing more happens. */
        CLOSED,
    }

    /** What to do with an {@code AUT} frame received by a server. */
    enum AutDecision {
        FIRST,
        REAUTHENTICATE,
        /** A second {@code AUT} while one is pending: close the session. */
        CONCURRENT,
        /** The session is already being closed. */
        IGNORE,
    }

    private volatile State state = State.NONE;
    private boolean reauthenticating;
    private volatile Principal principal;
    private volatile Instant expiresAt;
    private volatile Map<String, Object> details = Map.of();

    /**
     * Whether the session was "connected" in the application's sense: the connected hook fired or is about to fire,
     * so the disconnected hook has to fire too. Volatile rather than guarded by this monitor: it is read when the
     * session closes, which may happen on a thread that holds the session's send lock while another thread holds this
     * monitor and waits for that send lock (to send an ACK, see {@link #authenticate}).
     */
    private volatile boolean connected;

    /** Sends the ACK that completes an authentication; the state is committed only if it succeeds. */
    @FunctionalInterface
    interface AckSender {
        void send() throws IOException;

        AckSender NONE = () -> {
        };
    }

    synchronized void require() {
        state = State.PENDING;
    }

    synchronized void markConnectedWithoutAuthentication() {
        connected = true;
    }

    boolean isConnected() {
        return connected;
    }

    /** Whether messages may be exchanged: the session needs no authentication or is authenticated. Lock-free. */
    boolean admitsMessages() {
        State current = state;
        return current == State.NONE || current == State.AUTHENTICATED;
    }

    /**
     * Like {@link #admitsMessages()}, but waits for an authentication being committed right now (its ACK is being
     * sent). For the receiving thread's gate only: a client may send a MSG as soon as it has the ACK, and that MSG
     * must find the session authenticated.
     */
    synchronized boolean admitsMessagesAfterCommit() {
        return admitsMessages();
    }

    boolean isAuthenticated() {
        return state == State.AUTHENTICATED;
    }

    boolean isPending() {
        State current = state;
        return current == State.PENDING || current == State.AUTHENTICATING;
    }

    /** Server: whether the first AUT is being authenticated (and the session neither timed out nor closed). */
    boolean isAuthenticating() {
        return state == State.AUTHENTICATING;
    }

    Principal getPrincipal() {
        return principal;
    }

    Instant getExpiresAt() {
        return expiresAt;
    }

    Map<String, Object> getDetails() {
        return details;
    }

    synchronized AutDecision onAutReceived() {
        switch (state) {
            case PENDING:
                state = State.AUTHENTICATING;
                return AutDecision.FIRST;
            case AUTHENTICATED:
                if (reauthenticating) {
                    return AutDecision.CONCURRENT;
                }
                reauthenticating = true;
                return AutDecision.REAUTHENTICATE;
            case AUTHENTICATING:
                return AutDecision.CONCURRENT;
            default:
                return AutDecision.IGNORE;
        }
    }

    /** Completes a first authentication that sends no ACK (server: by handshake; client: by the server's ACK). */
    synchronized boolean authenticate(Principal principal, Instant expiresAt, Map<String, Object> details) {
        try {
            return authenticate(principal, expiresAt, details, AckSender.NONE);
        }
        catch (IOException e) {
            throw new IllegalStateException(e); // AckSender.NONE does not throw
        }
    }

    /**
     * Completes a first authentication (server: by handshake or {@code AUT}; client: by the {@code ACK}). Returns
     * false if the session was closed meanwhile (e.g. by the auth timeout), in which case nothing may be sent and no
     * hook fired.
     * <p>
     * The state is committed only after the {@code ACK} was sent; if sending fails, the session stays
     * unauthenticated. Holding the monitor while sending makes ACK and commit atomic for everyone else: a {@code MSG}
     * the client sends right after the {@code ACK} waits at the gate ({@link #admitsMessages()}) until the commit, and
     * the auth timeout can't strike in between.
     */
    synchronized boolean authenticate(Principal principal, Instant expiresAt, Map<String, Object> details,
            AckSender ackSender) throws IOException {
        if (state != State.PENDING && state != State.AUTHENTICATING) {
            return false;
        }
        ackSender.send();
        this.principal = principal;
        this.expiresAt = expiresAt;
        this.details = details;
        this.connected = true;
        // Last: publishes the fields above to lock-free readers.
        state = State.AUTHENTICATED;
        return true;
    }

    /**
     * Server: a re-authentication with the same principal succeeded; replaces (or removes) the expiry once the
     * {@code ACK} was sent. If sending fails, the previous expiry stays.
     */
    synchronized boolean reauthenticate(Instant expiresAt, Map<String, Object> details, AckSender ackSender)
            throws IOException {
        reauthenticating = false;
        if (state != State.AUTHENTICATED) {
            return false;
        }
        ackSender.send();
        this.expiresAt = expiresAt;
        this.details = details;
        return true;
    }

    /** Server: a re-authentication failed without closing the session. */
    synchronized void endReauthentication() {
        reauthenticating = false;
    }

    /** Client: a later {@code ACK} replaces the expiry and details. */
    synchronized void refresh(Instant expiresAt, Map<String, Object> details) {
        if (state == State.AUTHENTICATED) {
            this.expiresAt = expiresAt;
            this.details = details;
        }
    }

    /** The auth timeout elapsed: returns true if the session is not authenticated and must be closed. */
    synchronized boolean timeout() {
        if (state == State.PENDING || state == State.AUTHENTICATING) {
            state = State.CLOSED;
            return true;
        }
        return false;
    }

    /** Returns true if the authentication has expired at {@code now} and the session must be closed. */
    synchronized boolean expire(Instant now) {
        if (state == State.AUTHENTICATED && expiresAt != null && !now.isBefore(expiresAt)) {
            state = State.CLOSED;
            return true;
        }
        return false;
    }

    synchronized void close() {
        if (state != State.NONE) {
            state = State.CLOSED;
        }
    }
}
