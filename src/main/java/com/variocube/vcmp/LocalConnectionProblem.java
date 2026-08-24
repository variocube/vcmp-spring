package com.variocube.vcmp;

import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.server.ResponseStatusException;

import lombok.val;

/**
 * Marks a {@link ProblemDetail} as a connection-level problem detected locally by this VCMP instance —
 * no session, session closed, or a transient failure while sending on an open session — as opposed to
 * a NAK received from the peer, or a deterministic local failure (e.g. a message that cannot be
 * serialized) that retrying cannot fix.
 * <p>
 * The marker is a ProblemDetail property that never crosses the wire: it is stripped when a NAK frame
 * is serialized ({@code VcmpHandler#nak}) and removed from freshly parsed NAK payloads
 * ({@code VcmpHandler#parseProblemDetail}) as well as top-level ProblemDetail ACK results, so a peer
 * can neither observe nor forge it on those channels.
 * <p>
 * Consumers use {@link #isTransportFailure(ErrorResponseException)} to distinguish "the peer never saw
 * this message and retrying later may succeed" from "this message failed for good" — instead of
 * matching on status codes, which a peer's NAK may carry for its own reasons.
 */
public final class LocalConnectionProblem {

    /** Property key on {@link ProblemDetail#getProperties()} marking a local connection problem. */
    public static final String PROPERTY = "vcmp-local-connection";

    private LocalConnectionProblem() {
    }

    /**
     * Marks the given ProblemDetail as a locally detected connection problem.
     *
     * @param problemDetail the ProblemDetail to mark
     * @return the same instance, for chaining
     */
    public static ProblemDetail mark(ProblemDetail problemDetail) {
        problemDetail.setProperty(PROPERTY, true);
        return problemDetail;
    }

    /**
     * Returns whether the ProblemDetail is a connection problem detected locally by this VCMP instance.
     * Null-safe: returns false for null or unmarked ProblemDetails.
     *
     * @param problemDetail the ProblemDetail to check, may be null
     * @return true if the ProblemDetail carries the local-connection marker
     */
    public static boolean isMarked(ProblemDetail problemDetail) {
        return problemDetail != null
                && problemDetail.getProperties() != null
                && Boolean.TRUE.equals(problemDetail.getProperties().get(PROPERTY));
    }

    /**
     * Returns whether the failure is transport-level, meaning the peer never saw the message and
     * retrying later may succeed: either a {@link ResponseStatusException} thrown locally by
     * {@link VcmpCallback#await} (timeout, interrupt), or a ProblemDetail carrying the
     * local-connection marker. A peer NAK — whatever its status code — and a deterministic local
     * failure (e.g. a serialization error) are not transport failures: retrying them cannot help.
     *
     * @param e the exception a {@link VcmpCallback} await threw
     * @return true if the failure is a retryable transport-level condition
     */
    public static boolean isTransportFailure(ErrorResponseException e) {
        return e instanceof ResponseStatusException || isMarked(e.getBody());
    }

    /**
     * Removes the marker in place. Used on freshly parsed, unshared instances only — a peer must not
     * be able to forge the marker.
     */
    static void unmark(ProblemDetail problemDetail) {
        if (problemDetail.getProperties() != null) {
            problemDetail.getProperties().remove(PROPERTY);
        }
    }

    /**
     * Returns a ProblemDetail safe to serialize into a NAK frame: the same instance if unmarked,
     * otherwise a copy without the marker. A copy rather than in-place removal, because the instance
     * may be shared with chained local NAK handlers running concurrently (see the mutability note on
     * {@code VcmpSession#failPendingCallbacks}).
     */
    static ProblemDetail stripForWire(ProblemDetail problemDetail) {
        if (!isMarked(problemDetail)) {
            return problemDetail;
        }
        val copy = ProblemDetail.forStatus(problemDetail.getStatus());
        copy.setType(problemDetail.getType());
        copy.setTitle(problemDetail.getTitle());
        copy.setDetail(problemDetail.getDetail());
        copy.setInstance(problemDetail.getInstance());
        if (problemDetail.getProperties() != null) {
            problemDetail.getProperties().forEach((key, value) -> {
                if (!PROPERTY.equals(key)) {
                    copy.setProperty(key, value);
                }
            });
        }
        return copy;
    }
}
