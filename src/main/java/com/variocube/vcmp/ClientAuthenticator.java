package com.variocube.vcmp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.variocube.vcmp.ServerAuthenticator.problem;

/**
 * The client side of VCMP session authentication ({@code AUT}): sends the credential first on every session and
 * reports it "connected" only after the server's {@code ACK}. It schedules no re-authentication: the application
 * triggers one with {@link #reauthenticate()} (vcmp-spring#34, 2026-11-26). The credential is never logged.
 * <p>
 * Only one {@code AUT} is in flight per session. Re-authentications requested while one is pending (the first
 * {@code AUT} or a re-authentication) are served together by one further {@code AUT} after the pending answer, with a
 * fresh credential.
 */
@Slf4j
final class ClientAuthenticator {

    static final String TIMEOUT_TITLE = "Authentication timeout";

    private final VcmpHandler handler;
    private final VcmpClientAuthentication configuration;
    private final ObjectMapper objectMapper;

    /** Guards {@link #session}, {@link #inFlight} and {@link #queued}. */
    private final Object lock = new Object();

    /** The client's current session (the latest one established), or {@code null} before the first. */
    private VcmpSession session;

    /** Whether an authentication attempt (credential request, AUT, answer) is in flight on {@link #session}. */
    private boolean inFlight;

    /** The latest attempt started, so that a closing session can end it at once (503) rather than let it hang. */
    private volatile Attempt currentAttempt;

    /** Re-authentications requested while an attempt is in flight: served together by one further attempt. */
    private List<CompletableFuture<Map<String, Object>>> queued = new ArrayList<>();

    ClientAuthenticator(VcmpHandler handler, VcmpClientAuthentication configuration, ObjectMapper objectMapper) {
        this.handler = handler;
        this.configuration = configuration;
        this.objectMapper = objectMapper;
    }

    /** Called when the session is established: nothing but the AUT is sent until the server's ACK. */
    void sessionOpened(VcmpSession newSession) {
        newSession.getAuthentication().require();
        List<CompletableFuture<Map<String, Object>>> stale;
        synchronized (lock) {
            stale = queued;
            queued = new ArrayList<>();
            session = newSession;
            inFlight = true;
        }
        // Requests for an earlier session: nothing to re-authenticate any more (this session authenticates anyway).
        if (!stale.isEmpty()) {
            Executor.getExecutor().submit(() -> stale.forEach(future -> future.complete(null)));
        }
        Executor.getExecutor().submit(() -> authenticate(new Attempt(newSession, false, List.of())));
    }

    /** Called when a session closed: ends its pending attempt, if any, with 503. */
    void sessionClosed(VcmpSession closedSession) {
        val attempt = currentAttempt;
        if (attempt != null && attempt.session == closedSession && attempt.end()) {
            Executor.getExecutor().submit(() -> failed(attempt, Failure.SESSION_CLOSED, sessionClosedProblem()));
        }
    }

    private static ProblemDetail sessionClosedProblem() {
        return LocalConnectionProblem.mark(problem(HttpStatus.SERVICE_UNAVAILABLE, "Session closed",
                "The session was closed before the AUT was answered."));
    }

    /**
     * Requests a fresh credential and sends it in an {@code AUT} on the authenticated session. Completes with the
     * {@code ACK}'s payload members (empty if it has none), exceptionally with an {@link ErrorResponseException}
     * carrying the problem (also reported to the failure handler), or with {@code null} if nothing was sent because
     * there is no authenticated session.
     */
    CompletableFuture<Map<String, Object>> reauthenticate() {
        val future = new CompletableFuture<Map<String, Object>>();
        Attempt attempt = null;
        synchronized (lock) {
            val current = session;
            if (current != null && current.isOpen()) {
                if (inFlight) {
                    queued.add(future);
                    return future;
                }
                if (current.isAuthenticated()) {
                    inFlight = true;
                    attempt = new Attempt(current, true, List.of(future));
                }
            }
        }
        if (attempt == null) {
            // Connecting or disconnected: the next connect asks the credential provider anyway.
            future.complete(null);
            return future;
        }
        val started = attempt;
        Executor.getExecutor().submit(() -> authenticate(started));
        return future;
    }

    /** Why an authentication attempt failed: decides whether the session is closed. */
    private enum Failure {
        /** The server answered with NAK. */
        NAK,
        /** A sent AUT got no answer within the answer timeout. */
        ANSWER_TIMEOUT,
        /** The credential supplier did not complete within the answer timeout; nothing was sent. */
        CREDENTIAL_TIMEOUT,
        /** The credential supplier failed, or its credential is no JSON object; nothing was sent. */
        CREDENTIAL,
        /** The session closed while the attempt was pending. */
        SESSION_CLOSED,
    }

    /**
     * One authentication attempt: obtaining the credential, sending the AUT and waiting for the answer. It ends exactly
     * once: with the answer, a failure, or the answer timeout, which starts when the credential is requested (so a
     * supplier that never completes ends too). Every end leads to {@link #finish}.
     */
    private static final class Attempt {
        final VcmpSession session;
        final boolean reauthentication;
        final List<CompletableFuture<Map<String, Object>>> waiters;
        private final AtomicBoolean ended = new AtomicBoolean();
        private volatile String frameId;

        Attempt(VcmpSession session, boolean reauthentication, List<CompletableFuture<Map<String, Object>>> waiters) {
            this.session = session;
            this.reauthentication = reauthentication;
            this.waiters = waiters;
        }

        boolean end() {
            return ended.compareAndSet(false, true);
        }
    }

    private void authenticate(Attempt attempt) {
        currentAttempt = attempt;
        val session = attempt.session;
        if (!session.isOpen()) {
            if (attempt.end()) {
                failed(attempt, Failure.SESSION_CLOSED, sessionClosedProblem());
            }
            return;
        }
        Executor.getExecutor().schedule(() -> {
            if (attempt.end()) {
                val frameId = attempt.frameId;
                if (frameId != null) {
                    val timeout = LocalConnectionProblem.mark(problem(HttpStatus.GATEWAY_TIMEOUT, TIMEOUT_TITLE,
                            "The server did not answer the AUT in time."));
                    session.failPendingCallback(frameId, timeout);
                    failed(attempt, Failure.ANSWER_TIMEOUT, timeout);
                }
                else {
                    failed(attempt, Failure.CREDENTIAL_TIMEOUT, LocalConnectionProblem.mark(problem(
                            HttpStatus.GATEWAY_TIMEOUT, TIMEOUT_TITLE,
                            "The credential supplier did not complete in time.")));
                }
            }
        }, configuration.answerTimeout().toMillis(), TimeUnit.MILLISECONDS);

        CompletionStage<?> credential;
        try {
            credential = configuration.credentials().get();
            if (credential == null) {
                throw new IllegalStateException("The credential supplier returned null.");
            }
        }
        catch (Throwable e) {
            credentialFailed(attempt, e);
            return;
        }
        // On a VCMP worker, whatever thread completes the credential: the failure handler and the waiters' completions
        // must not run on (and block) the credential method's own executor.
        credential.whenCompleteAsync((value, error) -> {
            if (error != null) {
                credentialFailed(attempt, VcmpHandler.unwrap(error));
            }
            else {
                send(attempt, value);
            }
        }, WORKERS);
    }

    /** The VCMP worker pool as a {@link java.util.concurrent.Executor}. */
    private static final java.util.concurrent.Executor WORKERS = task -> Executor.getExecutor().submit(task);

    private void credentialFailed(Attempt attempt, Throwable error) {
        if (attempt.end()) {
            log.warn("The credential supplier failed", error);
            failed(attempt, Failure.CREDENTIAL, problem(HttpStatus.INTERNAL_SERVER_ERROR, "Credential unavailable",
                    "The credential supplier failed (" + error.getClass().getSimpleName() + ")."));
        }
    }

    private void send(Attempt attempt, Object credential) {
        val session = attempt.session;
        if (!session.isOpen()) {
            if (attempt.end()) {
                failed(attempt, Failure.SESSION_CLOSED, sessionClosedProblem());
            }
            return;
        }
        String payload;
        try {
            val node = credential != null ? objectMapper.valueToTree(credential) : null;
            if (node == null || !((JsonNode) node).isObject()) {
                malformedCredential(attempt, "The credential must serialize to a JSON object.");
                return;
            }
            payload = objectMapper.writeValueAsString(node);
        }
        catch (Exception e) {
            // Deliberately without the exception's message: it may quote the credential.
            malformedCredential(attempt,
                    "The credential could not be serialized (" + e.getClass().getSimpleName() + ").");
            return;
        }

        val frame = VcmpFrame.createAuthenticate(payload);
        attempt.frameId = frame.getId();
        if (attempt.ended.get()) {
            // Timed out while the credential was being obtained: too late to send it.
            return;
        }
        val callback = session.sendAuthenticate(frame);

        // The ACK may be settled on the receiving thread: change the state there, so a MSG right after the ACK
        // finds the session authenticated, and leave everything that may block to the worker pool.
        callback.onAck(result -> {
            if (attempt.end()) {
                authenticated(attempt, result);
            }
        });
        callback.onNak(problemDetail -> {
            if (attempt.end()) {
                Executor.getExecutor().submit(() -> failed(attempt, Failure.NAK, problemDetail));
            }
        });
    }

    private void malformedCredential(Attempt attempt, String detail) {
        if (attempt.end()) {
            failed(attempt, Failure.CREDENTIAL,
                    problem(HttpStatus.INTERNAL_SERVER_ERROR, "Malformed credential", detail));
        }
    }

    private void authenticated(Attempt attempt, JsonNode ack) {
        val session = attempt.session;
        Long expiresIn = null;
        Map<String, Object> details = Map.of();
        if (ack != null && !ack.isNull()) {
            if (ack.isObject()) {
                details = objectMapper.convertValue(ack, new TypeReference<Map<String, Object>>() {
                });
                val expiresInNode = ack.get(VcmpAuthentication.EXPIRES_IN);
                if (expiresInNode != null) {
                    if (expiresInNode.isIntegralNumber() && expiresInNode.canConvertToLong()
                            && expiresInNode.asLong() >= 0) {
                        expiresIn = expiresInNode.asLong();
                    }
                    else {
                        log.warn("Session {}: the ACK to the AUT has an invalid expiresIn; treating it as absent.",
                                session.getId());
                    }
                }
            }
            else {
                log.warn("Session {}: the ACK payload to the AUT is no JSON object; treating it as no payload.",
                        session.getId());
            }
        }
        // Passed to the application (getAuthenticationExpiresAt / -Details); the client sets no timer from it.
        val expiresAt = expiresIn != null ? Instant.now().plusMillis(expiresIn) : null;
        val result = details;

        if (!attempt.reauthentication) {
            if (!session.getAuthentication().authenticate(null, expiresAt, details)) {
                Executor.getExecutor().submit(() -> finish(attempt, null, null, () -> {
                }));
                return;
            }
            log.info("Session {} authenticated.", session.getId());
            Executor.getExecutor().submit(() -> finish(attempt, result, null, () -> {
                runHandler("authenticated handler", configuration.authenticatedHandler());
                handler.invokeConnected(session);
            }));
        }
        else {
            session.getAuthentication().refresh(expiresAt, details);
            log.debug("Session {} re-authenticated.", session.getId());
            Executor.getExecutor().submit(() -> finish(attempt, result, null,
                    () -> runHandler("authenticated handler", configuration.authenticatedHandler())));
        }
    }

    private static void runHandler(String name, Runnable runnable) {
        try {
            runnable.run();
        }
        catch (Throwable e) {
            log.error("Error in {}", name, e);
        }
    }

    private void failed(Attempt attempt, Failure failure, ProblemDetail problemDetail) {
        val session = attempt.session;
        log.warn("Authentication of session {} failed: {} {}", session.getId(), problemDetail.getStatus(),
                problemDetail.getTitle());

        if (failure == Failure.ANSWER_TIMEOUT) {
            // No answer to a sent AUT (first or re-authentication): it may still be pending on the server, which
            // closes the session on a second one (one AUT at a time). Close it and reconnect with a fresh credential.
            session.closeForAuthenticationAsClient(ServerAuthenticator.REASON_TIMEOUT);
        }
        else if (!attempt.reauthentication && failure != Failure.SESSION_CLOSED) {
            // The first authentication failed: give up this session; reconnecting follows the normal rules.
            session.closeForAuthenticationAsClient(failure == Failure.CREDENTIAL_TIMEOUT
                    ? ServerAuthenticator.REASON_TIMEOUT
                    : ServerAuthenticator.REASON_FAILED);
        }
        // A re-authentication that was NAKed, or whose credential could not be obtained, keeps the session: its
        // current authentication is still valid until its expiry. No automatic retry; the application may call again.
        finish(attempt, null, problemDetail,
                () -> runHandler("authentication failure handler",
                        () -> configuration.failureHandler().accept(problemDetail)));
    }

    /**
     * Ends an attempt. Runs on a worker thread, in this order:
     * <ol>
     *     <li>the state: the attempt is over; the re-authentications requested meanwhile are handed over atomically to
     *     one further attempt (if the session is still authenticated), which is started right away on another
     *     worker;</li>
     *     <li>the attempt's waiters, and the requests that can't be served (no authenticated session, {@code null});</li>
     *     <li>the application's handlers (failure handler, authenticated handler, connected hook).</li>
     * </ol>
     * The waiters complete before the handlers, so a handler that blocks on a future of this very attempt can't
     * deadlock either.
     * The handlers run last, after the attempt is over and the next one is on its way, so a handler may call
     * {@code reauthenticate()} and even block on it: its call either starts an attempt of its own or is queued
     * behind the one already started, and is served without waiting for the handler.
     */
    private void finish(Attempt attempt, Map<String, Object> result, ProblemDetail problemDetail,
            Runnable handlers) {
        List<CompletableFuture<Map<String, Object>>> next = null;
        List<CompletableFuture<Map<String, Object>>> dropped = List.of();
        synchronized (lock) {
            // Unless a newer session took over (its connect reset the queue).
            if (attempt.session == session) {
                if (!queued.isEmpty() && session.isOpen() && session.isAuthenticated()) {
                    next = queued;
                }
                else {
                    dropped = queued;
                    inFlight = false;
                }
                queued = new ArrayList<>();
            }
        }
        if (next != null) {
            val further = new Attempt(attempt.session, true, next);
            Executor.getExecutor().submit(() -> authenticate(further));
        }
        for (val waiter : attempt.waiters) {
            if (problemDetail != null) {
                waiter.completeExceptionally(toException(problemDetail));
            }
            else {
                waiter.complete(result);
            }
        }
        dropped.forEach(future -> future.complete(null));
        handlers.run();
    }

    private static ErrorResponseException toException(ProblemDetail problemDetail) {
        val status = problemDetail.getStatus() >= 100 && problemDetail.getStatus() <= 999
                ? HttpStatusCode.valueOf(problemDetail.getStatus())
                : HttpStatusCode.valueOf(500);
        return new ErrorResponseException(status, problemDetail, null);
    }
}
