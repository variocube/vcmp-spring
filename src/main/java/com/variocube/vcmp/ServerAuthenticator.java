package com.variocube.vcmp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The server side of VCMP session authentication ({@code AUT}) for one endpoint: invokes its
 * {@link VcmpAuthenticator} and {@link VcmpHandshakeAuthenticator}, and enforces the auth timeout, the expiry and the
 * re-authentication rules. The credential is never logged, neither are messages of exceptions from parsing it (they may
 * quote it).
 */
@Slf4j
final class ServerAuthenticator {

    static final String REASON_REQUIRED = "authentication required";
    static final String REASON_TIMEOUT = "authentication timeout";
    static final String REASON_FAILED = "authentication failed";
    static final String REASON_EXPIRED = "authentication expired";
    static final String REASON_PRINCIPAL_CHANGED = "principal changed";
    static final String REASON_CONCURRENT = "concurrent authentication";

    private final VcmpHandler handler;
    private final Object target;
    private final Method authenticator;
    private final Method handshakeAuthenticator;
    private final JavaType credentialType;
    private final Duration timeout;
    private final ObjectMapper objectMapper;

    /**
     * The clock that judges a credential's expiry when it is checked and when the ACK's {@code expiresIn} is computed.
     * A seam for tests, which need the expiry to pass exactly between the two.
     */
    private Clock clock = Clock.systemUTC();

    private final AtomicLong handshakeAuthentications = new AtomicLong();
    private final AtomicLong autAuthentications = new AtomicLong();

    ServerAuthenticator(VcmpHandler handler, Object target, Duration timeout, ObjectMapper objectMapper) {
        this.handler = handler;
        this.target = target;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.objectMapper = objectMapper;
        val targetClass = ClassUtils.getTargetClass(target);
        this.authenticator = findAnnotatedMethod(targetClass, VcmpAuthenticator.class);
        if (authenticator == null) {
            throw new IllegalStateException(targetClass.getSimpleName()
                    + " is an authenticated endpoint but has no @VcmpAuthenticator method.");
        }
        this.handshakeAuthenticator = findAnnotatedMethod(targetClass, VcmpHandshakeAuthenticator.class);
        this.credentialType = validateAuthenticator(authenticator, objectMapper);
        if (handshakeAuthenticator != null) {
            validateHandshakeAuthenticator(handshakeAuthenticator);
        }
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    boolean hasHandshakeAuthenticator() {
        return handshakeAuthenticator != null;
    }

    /**
     * Returns the single public method of the class annotated with the given annotation, or {@code null}.
     */
    static Method findAnnotatedMethod(Class<?> targetClass, Class<? extends Annotation> annotation) {
        Method found = null;
        for (Method method : targetClass.getMethods()) {
            if (method.isAnnotationPresent(annotation)) {
                if (found != null) {
                    throw new IllegalStateException(targetClass.getSimpleName() + " has more than one @"
                            + annotation.getSimpleName() + " method.");
                }
                found = method;
            }
        }
        return found;
    }

    /** Validates the authenticator's signature and returns the type of its credential parameter, if any. */
    private static JavaType validateAuthenticator(Method method, ObjectMapper objectMapper) {
        requireAuthenticationReturnType(method);
        JavaType credentialType = null;
        val parameterTypes = method.getGenericParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (method.getParameterTypes()[i] == VcmpSession.class) {
                continue;
            }
            if (credentialType != null) {
                throw new IllegalStateException("@VcmpAuthenticator " + method.getName()
                        + " may have only one credential parameter (besides a VcmpSession).");
            }
            credentialType = objectMapper.getTypeFactory().constructType(parameterTypes[i]);
        }
        return credentialType;
    }

    private static void validateHandshakeAuthenticator(Method method) {
        requireAuthenticationReturnType(method);
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (parameterType != VcmpHandshake.class) {
                throw new IllegalStateException("@VcmpHandshakeAuthenticator " + method.getName()
                        + " may only take a VcmpHandshake.");
            }
        }
    }

    private static void requireAuthenticationReturnType(Method method) {
        if (method.getReturnType() != VcmpAuthentication.class) {
            throw new IllegalStateException(method.getName() + " must return a VcmpAuthentication.");
        }
    }

    /**
     * Called on the container thread when the session is established, before any frame of it is handled. Returns
     * true if the session is authenticated by its handshake already.
     */
    boolean sessionOpened(VcmpSession session, VcmpHandshake handshake) {
        session.getAuthentication().require();
        Executor.getExecutor().schedule(() -> {
            if (session.getAuthentication().timeout()) {
                session.closeForPolicyViolation(REASON_TIMEOUT);
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);

        if (handshakeAuthenticator == null) {
            return false;
        }
        VcmpAuthentication authentication;
        try {
            authentication = (VcmpAuthentication) invoke(handshakeAuthenticator, handshake);
            if (authentication != null && isExpired(authentication)) {
                throw new IllegalStateException("The handshake credential has expired.");
            }
        }
        catch (Throwable e) {
            log.info("Session {}: invalid handshake credential ({}).", session.getId(), e.getClass().getSimpleName());
            session.closeForPolicyViolation(REASON_FAILED);
            return false;
        }
        if (authentication == null) {
            log.debug("Session {}: no handshake credential, waiting for AUT.", session.getId());
            return false;
        }
        if (!session.getAuthentication().authenticate(authentication.getPrincipal(), authentication.getExpiresAt(),
                authentication.getDetails())) {
            return false;
        }
        log.info("Session {} authenticated by handshake ({} by handshake, {} by AUT since start).", session.getId(),
                handshakeAuthentications.incrementAndGet(), autAuthentications.get());
        scheduleExpiry(session, authentication.getExpiresAt());
        return true;
    }

    /**
     * Handles an {@code AUT} frame on the receiving thread: decides synchronously (so that the order of frames is
     * respected) and authenticates asynchronously.
     */
    void handleAut(VcmpSession session, VcmpFrame frame) {
        switch (session.getAuthentication().onAutReceived()) {
            case FIRST -> Executor.getExecutor().submit(() -> handler.runThrottled(() -> {
                // In a connect storm the AUT may have waited for the throttle beyond the auth timeout: don't spend a
                // permit (and the authenticator's DB work) on a session that is gone.
                if (!session.isOpen() || !session.getAuthentication().isAuthenticating()) {
                    log.debug("Session {}: skipping the authentication of a closed session.", session.getId());
                    return;
                }
                if (authenticateFirst(session, frame)) {
                    handler.invokeConnected(session);
                }
            }));
            case REAUTHENTICATE -> Executor.getExecutor().submit(() -> reauthenticate(session, frame));
            case CONCURRENT -> session.closeForPolicyViolation(REASON_CONCURRENT);
            case IGNORE -> {
            }
        }
    }

    /** Returns true if the session is now authenticated and connected. */
    private boolean authenticateFirst(VcmpSession session, VcmpFrame frame) {
        Outcome outcome = authenticate(session, frame);
        if (outcome.rejection != null) {
            handler.nak(session, frame.getId(), outcome.rejection);
            session.closeForPolicyViolation(REASON_FAILED);
            return false;
        }
        val authentication = outcome.authentication;
        // Everything after the authenticator accepted the credential can still fail (the ACK payload may not
        // serialize, sending may fail). The session is authenticated only once the ACK is out; any failure before
        // fails closed like an authenticator failure: NAK 500 and close, never a half-authenticated session.
        try {
            val ack = ackFrame(frame.getId(), authentication);
            if (!session.getAuthentication().authenticate(authentication.getPrincipal(),
                    authentication.getExpiresAt(), authentication.getDetails(), () -> session.sendFrame(ack))) {
                // Closed meanwhile, e.g. by the auth timeout: neither ACK nor connected hook.
                return false;
            }
        }
        catch (ExpiredBeforeAckException e) {
            log.info("Session {}: the credential expired before the ACK.", session.getId());
            handler.nak(session, frame.getId(), invalidCredential());
            session.closeForPolicyViolation(REASON_FAILED);
            return false;
        }
        catch (Exception e) {
            log.error("Session {}: completing the authentication failed.", session.getId(), e);
            handler.nak(session, frame.getId(), unexpectedFailure());
            session.closeForPolicyViolation(REASON_FAILED);
            return false;
        }
        log.info("Session {} authenticated by AUT ({} by handshake, {} by AUT since start).", session.getId(),
                handshakeAuthentications.get(), autAuthentications.incrementAndGet());
        scheduleExpiry(session, authentication.getExpiresAt());
        return true;
    }

    private void reauthenticate(VcmpSession session, VcmpFrame frame) {
        val state = session.getAuthentication();
        Outcome outcome = authenticate(session, frame);
        if (outcome.rejection != null) {
            // A credential that was valid isn't revoked by a failed refresh: the session stays open until its
            // current expiry, so the client can retry.
            state.endReauthentication();
            handler.nak(session, frame.getId(), outcome.rejection);
            return;
        }
        val authentication = outcome.authentication;
        try {
            if (!Objects.equals(state.getPrincipal(), authentication.getPrincipal())) {
                // A session never changes identity: the application registered it (and pushes to it) under the
                // first principal.
                state.endReauthentication();
                log.info("Session {}: re-authentication as another principal.", session.getId());
                handler.nak(session, frame.getId(), problem(HttpStatus.FORBIDDEN, "Principal changed",
                        "A session cannot change its principal."));
                session.closeForPolicyViolation(REASON_PRINCIPAL_CHANGED);
                return;
            }
            val ack = ackFrame(frame.getId(), authentication);
            if (!state.reauthenticate(authentication.getExpiresAt(), authentication.getDetails(),
                    () -> session.sendFrame(ack))) {
                return;
            }
        }
        catch (ExpiredBeforeAckException e) {
            // Like a refresh with an expired credential: rejected, the previous authentication stays.
            state.endReauthentication();
            log.info("Session {}: the refreshed credential expired before the ACK.", session.getId());
            handler.nak(session, frame.getId(), invalidCredential());
            return;
        }
        catch (Exception e) {
            // E.g. the principal's equals() threw, or the ACK payload does not serialize: fail closed like an
            // authenticator failure; the previous authentication stays until its expiry.
            state.endReauthentication();
            log.error("Session {}: completing the re-authentication failed.", session.getId(), e);
            handler.nak(session, frame.getId(), unexpectedFailure());
            return;
        }
        log.debug("Session {} re-authenticated.", session.getId());
        scheduleExpiry(session, authentication.getExpiresAt());
    }

    private record Outcome(VcmpAuthentication authentication, ProblemDetail rejection) {
    }

    private Outcome authenticate(VcmpSession session, VcmpFrame frame) {
        Object credential;
        try {
            credential = parseCredential(frame.getPayload());
        }
        catch (MalformedCredentialException e) {
            log.info("Session {}: malformed AUT credential.", session.getId());
            return new Outcome(null, problem(HttpStatus.BAD_REQUEST, "Malformed credential", e.getMessage()));
        }
        try {
            val authentication = (VcmpAuthentication) invoke(authenticator, session, credential);
            if (authentication == null || isExpired(authentication)) {
                log.info("Session {}: invalid AUT credential.", session.getId());
                return new Outcome(null, invalidCredential());
            }
            return new Outcome(authentication, null);
        }
        catch (Throwable e) {
            val cause = VcmpHandler.unwrap(e);
            val deliberate = VcmpHandler.resolveDeliberateStatus(cause);
            if (deliberate != null) {
                log.info("Session {}: AUT rejected with status {}.", session.getId(), deliberate.getStatus());
                return new Outcome(null, deliberate);
            }
            log.error("Session {}: authenticator failed.", session.getId(), cause);
            return new Outcome(null, unexpectedFailure());
        }
    }

    private Object parseCredential(String payload) throws MalformedCredentialException {
        if (payload == null) {
            throw new MalformedCredentialException("The AUT frame has no credential.");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(payload);
        }
        catch (Exception e) {
            // Deliberately without the exception's message: it may quote the payload.
            throw new MalformedCredentialException("The credential is not valid JSON.");
        }
        if (node == null || !node.isObject()) {
            throw new MalformedCredentialException("The credential must be a JSON object.");
        }
        if (credentialType == null) {
            return null;
        }
        try {
            return objectMapper.convertValue(node, credentialType);
        }
        catch (Exception e) {
            throw new MalformedCredentialException("The credential does not have the expected shape.");
        }
    }

    private static final class MalformedCredentialException extends Exception {
        MalformedCredentialException(String message) {
            super(message, null, false, false);
        }
    }

    private Object invoke(Method method, Object... available) throws Throwable {
        val parameterTypes = method.getParameterTypes();
        val args = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i] == VcmpSession.class || parameterTypes[i] == VcmpHandshake.class) {
                args[i] = find(parameterTypes[i], available);
            }
            else {
                // The credential: the only other parameter.
                args[i] = available.length > 1 ? available[1] : null;
            }
        }
        try {
            return method.invoke(target, args);
        }
        catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static Object find(Class<?> type, Object[] available) {
        for (Object candidate : available) {
            if (type.isInstance(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isExpired(VcmpAuthentication authentication) {
        return authentication.getExpiresAt() != null && !clock.instant().isBefore(authentication.getExpiresAt());
    }

    /**
     * Closes the session at the given instant unless a re-authentication has moved or removed its expiry by then.
     */
    private void scheduleExpiry(VcmpSession session, Instant expiresAt) {
        if (expiresAt == null) {
            return;
        }
        val delay = Math.max(0, Duration.between(Instant.now(), expiresAt).toMillis());
        Executor.getExecutor().schedule(() -> {
            val state = session.getAuthentication();
            if (state.expire(Instant.now())) {
                session.closeForPolicyViolation(REASON_EXPIRED);
            }
            else if (expiresAt.equals(state.getExpiresAt()) && session.isOpen()) {
                // The scheduler's clock is not the wall clock: fired a moment early, check again.
                scheduleExpiry(session, expiresAt);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * The ACK, serialized up front so that a payload that can't be serialized fails before anything is committed:
     * the endpoint's details and {@code expiresIn}; no payload if both are absent.
     */
    private VcmpFrame ackFrame(String id, VcmpAuthentication authentication)
            throws JsonProcessingException, ExpiredBeforeAckException {
        if (authentication.getExpiresAt() == null && authentication.getDetails().isEmpty()) {
            return VcmpFrame.createAck(id, null);
        }
        val payload = new LinkedHashMap<String, Object>(authentication.getDetails());
        if (authentication.getExpiresAt() != null) {
            val expiresIn = Duration.between(clock.instant(), authentication.getExpiresAt()).toMillis();
            if (expiresIn <= 0) {
                // The expiry passed since the check: an ACK with expiresIn 0 would authenticate a session that is
                // already expired. Reject it like an expired credential instead.
                throw new ExpiredBeforeAckException();
            }
            payload.put(VcmpAuthentication.EXPIRES_IN, expiresIn);
        }
        return VcmpFrame.createAck(id, objectMapper.writeValueAsString(payload));
    }

    private static final class ExpiredBeforeAckException extends Exception {
        ExpiredBeforeAckException() {
            super(null, null, false, false);
        }
    }

    private static ProblemDetail invalidCredential() {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication failed", "Invalid credential.");
    }

    private static ProblemDetail unexpectedFailure() {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Authentication failed", null);
    }

    static ProblemDetail problem(HttpStatus status, String title, String detail) {
        val problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(title);
        return problemDetail;
    }
}
