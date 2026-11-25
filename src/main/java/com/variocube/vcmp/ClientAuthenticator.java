package com.variocube.vcmp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.variocube.vcmp.ServerAuthenticator.problem;

/**
 * The client side of VCMP session authentication ({@code AUT}): sends the credential first on every session, reports
 * it "connected" only after the server's {@code ACK}, and re-authenticates before the expiry the server announced.
 * The credential is never logged.
 */
@Slf4j
final class ClientAuthenticator {

    static final String TIMEOUT_TITLE = "Authentication timeout";

    private final VcmpHandler handler;
    private final VcmpClientAuthentication configuration;
    private final ObjectMapper objectMapper;

    ClientAuthenticator(VcmpHandler handler, VcmpClientAuthentication configuration, ObjectMapper objectMapper) {
        this.handler = handler;
        this.configuration = configuration;
        this.objectMapper = objectMapper;
    }

    /** Called when the session is established: nothing but the AUT is sent until the server's ACK. */
    void sessionOpened(VcmpSession session) {
        session.getAuthentication().require();
        Executor.getExecutor().submit(() -> authenticate(session, null));
    }

    /** Why an authentication attempt failed: decides whether the session is closed or the refresh retried. */
    private enum Failure {
        /** The server answered with NAK. */
        NAK,
        /** No answer (or no credential from the supplier) within the answer timeout. */
        TIMEOUT,
        /** The credential supplier failed, or its credential is no JSON object. */
        CREDENTIAL,
    }

    /**
     * One authentication attempt: obtaining the credential, sending the AUT and waiting for the answer. It ends exactly
     * once: with the answer, a failure, or the answer timeout, which starts when the credential is requested (so a
     * supplier that never completes ends like an unanswered AUT).
     */
    private static final class Attempt {
        private final AtomicBoolean ended = new AtomicBoolean();
        private volatile String frameId;

        boolean end() {
            return ended.compareAndSet(false, true);
        }
    }

    /**
     * Requests a fresh credential and sends it in an {@code AUT}.
     *
     * @param deadlineNanos {@code null} for the first authentication of the session; for a refresh, the
     *                      {@link System#nanoTime()} at which the current authentication expires.
     */
    private void authenticate(VcmpSession session, Long deadlineNanos) {
        if (!session.isOpen()) {
            return;
        }
        val attempt = new Attempt();
        Executor.getExecutor().schedule(() -> {
            if (attempt.end()) {
                val frameId = attempt.frameId;
                val timeout = LocalConnectionProblem.mark(problem(HttpStatus.REQUEST_TIMEOUT, TIMEOUT_TITLE,
                        frameId != null
                                ? "The server did not answer the AUT in time."
                                : "The credential supplier did not complete in time."));
                if (frameId != null) {
                    session.failPendingCallback(frameId, timeout);
                }
                failed(session, deadlineNanos, Failure.TIMEOUT, timeout);
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
            credentialFailed(session, deadlineNanos, attempt, e);
            return;
        }
        credential.whenComplete((value, error) -> {
            if (error != null) {
                credentialFailed(session, deadlineNanos, attempt, VcmpHandler.unwrap(error));
            }
            else {
                send(session, deadlineNanos, attempt, value);
            }
        });
    }

    private void credentialFailed(VcmpSession session, Long deadlineNanos, Attempt attempt, Throwable error) {
        if (attempt.end()) {
            log.warn("The credential supplier failed", error);
            failed(session, deadlineNanos, Failure.CREDENTIAL, problem(HttpStatus.UNAUTHORIZED,
                    "Credential unavailable",
                    "The credential supplier failed (" + error.getClass().getSimpleName() + ")."));
        }
    }

    private void send(VcmpSession session, Long deadlineNanos, Attempt attempt, Object credential) {
        if (!session.isOpen()) {
            attempt.end();
            return;
        }
        String payload;
        try {
            val node = credential != null ? objectMapper.valueToTree(credential) : null;
            if (node == null || !((JsonNode) node).isObject()) {
                malformedCredential(session, deadlineNanos, attempt, "The credential must serialize to a JSON object.");
                return;
            }
            payload = objectMapper.writeValueAsString(node);
        }
        catch (Exception e) {
            // Deliberately without the exception's message: it may quote the credential.
            malformedCredential(session, deadlineNanos, attempt,
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
                authenticated(session, deadlineNanos, result);
            }
        });
        callback.onNak(problemDetail -> {
            if (attempt.end()) {
                Executor.getExecutor().submit(() -> failed(session, deadlineNanos, Failure.NAK, problemDetail));
            }
        });
    }

    private void malformedCredential(VcmpSession session, Long deadlineNanos, Attempt attempt, String detail) {
        if (attempt.end()) {
            failed(session, deadlineNanos, Failure.CREDENTIAL,
                    problem(HttpStatus.BAD_REQUEST, "Malformed credential", detail));
        }
    }

    private void authenticated(VcmpSession session, Long deadlineNanos, JsonNode ack) {
        val receivedNanos = System.nanoTime();
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
        val expiresAt = expiresIn != null ? Instant.now().plusMillis(expiresIn) : null;

        if (deadlineNanos == null) {
            if (!session.getAuthentication().authenticate(null, expiresAt, details)) {
                return;
            }
            log.info("Session {} authenticated.", session.getId());
            Executor.getExecutor().submit(() -> {
                configuration.authenticatedHandler().run();
                handler.invokeConnected(session);
            });
        }
        else {
            session.getAuthentication().refresh(expiresAt, details);
            log.debug("Session {} re-authenticated.", session.getId());
            Executor.getExecutor().submit(() -> configuration.authenticatedHandler().run());
        }
        if (expiresIn != null) {
            scheduleRefresh(session, receivedNanos + TimeUnit.MILLISECONDS.toNanos(expiresIn), expiresIn);
        }
    }

    private void scheduleRefresh(VcmpSession session, long deadlineNanos, long expiresInMillis) {
        val delay = configuration.refreshDelayMillis(expiresInMillis);
        Executor.getExecutor().schedule(() -> authenticate(session, deadlineNanos), delay, TimeUnit.MILLISECONDS);
    }

    private void failed(VcmpSession session, Long deadlineNanos, Failure failure, ProblemDetail problemDetail) {
        log.warn("Authentication of session {} failed: {} {}", session.getId(), problemDetail.getStatus(),
                problemDetail.getTitle());
        try {
            configuration.failureHandler().accept(problemDetail);
        }
        catch (Throwable e) {
            log.error("Error in authentication failure handler", e);
        }

        switch (failure) {
            case TIMEOUT -> {
                // No answer (first AUT or refresh): the AUT may still be pending on the server, which closes the
                // session on a second one (one AUT at a time). Close it and reconnect with a fresh credential.
                session.closeForAuthenticationAsClient(ServerAuthenticator.REASON_TIMEOUT);
            }
            case CREDENTIAL -> session.closeForAuthenticationAsClient(ServerAuthenticator.REASON_FAILED);
            case NAK -> {
                if (deadlineNanos == null) {
                    // NAK to the first AUT: give up this session; reconnecting follows the normal rules.
                    session.closeForAuthenticationAsClient(ServerAuthenticator.REASON_FAILED);
                    return;
                }
                // A NAK to a refresh is retried at a fixed interval until the expiry; after that the server closes
                // the session and the client reconnects as usual. After a principal change the server closes the
                // session, and authenticate() does nothing on a closed session, so there is no retry.
                val retryNanos = configuration.refreshRetryInterval().toNanos();
                if (System.nanoTime() + retryNanos - deadlineNanos < 0) {
                    Executor.getExecutor().schedule(() -> authenticate(session, deadlineNanos), retryNanos,
                            TimeUnit.NANOSECONDS);
                }
            }
        }
    }
}
