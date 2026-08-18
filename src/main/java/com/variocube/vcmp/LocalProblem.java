package com.variocube.vcmp;

import org.springframework.http.ProblemDetail;

import lombok.val;

/**
 * Marks a {@link ProblemDetail} as created locally by this VCMP instance — a transport-level failure
 * (no session, session closed, send failure) — as opposed to a NAK received from the peer.
 * <p>
 * The marker is a ProblemDetail property that never crosses the wire: it is stripped when a NAK frame
 * is serialized ({@code VcmpHandler#nak}) and removed from freshly parsed NAK payloads
 * ({@code VcmpHandler#parseProblemDetail}), so a peer can neither observe nor forge it.
 * <p>
 * Consumers use {@link #isLocal(ProblemDetail)} to distinguish "the peer never saw this message, retry
 * later" from "the peer rejected this message" — instead of matching on status codes, which a peer's
 * NAK may carry for its own reasons. Note that a timeout or interrupt while awaiting a reply surfaces
 * as a locally thrown {@link org.springframework.web.server.ResponseStatusException} from
 * {@link VcmpCallback#await}, not as a marked ProblemDetail.
 */
public final class LocalProblem {

    /** Property key on {@link ProblemDetail#getProperties()} marking a locally-created ProblemDetail. */
    public static final String PROPERTY = "vcmp-local";

    private LocalProblem() {
    }

    /**
     * Marks the given ProblemDetail as locally created.
     *
     * @param problemDetail the ProblemDetail to mark
     * @return the same instance, for chaining
     */
    public static ProblemDetail mark(ProblemDetail problemDetail) {
        problemDetail.setProperty(PROPERTY, true);
        return problemDetail;
    }

    /**
     * Returns whether the ProblemDetail was created locally by this VCMP instance.
     * Null-safe: returns false for null or unmarked ProblemDetails.
     *
     * @param problemDetail the ProblemDetail to check, may be null
     * @return true if the ProblemDetail carries the local marker
     */
    public static boolean isLocal(ProblemDetail problemDetail) {
        return problemDetail != null
                && problemDetail.getProperties() != null
                && Boolean.TRUE.equals(problemDetail.getProperties().get(PROPERTY));
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
        if (!isLocal(problemDetail)) {
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
