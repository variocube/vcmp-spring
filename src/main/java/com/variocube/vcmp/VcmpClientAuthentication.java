package com.variocube.vcmp;

import lombok.val;
import org.springframework.http.ProblemDetail;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * How a VCMP client authenticates its sessions with {@code AUT}. Built by the {@code VcmpConnectionManager} from the
 * client's {@code @VcmpCredential} method; see there.
 * <p>
 * An answer timeout or retry interval below {@link #MIN_INTERVAL}, or a negative lead time, is rejected with an
 * {@code IllegalArgumentException} (not clamped), so no configuration can cause a tight loop.
 *
 * @param credentials     Supplies the credential, called on every (re)connect and before every refresh. Completes with
 *                        an object that serializes to a JSON object (e.g. a record or a {@code Map}).
 * @param answerTimeout   How long an authentication attempt may take, from requesting the credential to the server's
 *                        answer to the {@code AUT}; at least 1 s.
 * @param refreshLeadTime How long before the expiry to re-authenticate; {@code null} for the default
 *                        {@code min(60 s, 20 % of expiresIn)}.
 * @param refreshRetryInterval How long to wait before retrying a refresh answered with {@code NAK}; retried until
 *                        the expiry; at least 1 s. {@code null} for the default of 5 s. (A refresh without an answer
 *                        closes the session, which then reconnects.)
 * @param failureHandler  Called with the problem whenever an authentication fails ({@code NAK}, answer timeout or a
 *                        failing credential supplier).
 * @param authenticatedHandler Called whenever an authentication succeeded (first or refresh); {@code null} for none.
 */
public record VcmpClientAuthentication(Supplier<CompletionStage<?>> credentials, Duration answerTimeout,
        Duration refreshLeadTime, Duration refreshRetryInterval, Consumer<ProblemDetail> failureHandler,
        Runnable authenticatedHandler) {

    /** The least answer timeout and refresh retry interval. */
    public static final Duration MIN_INTERVAL = Duration.ofSeconds(1);

    public static final Duration DEFAULT_ANSWER_TIMEOUT = Duration.ofSeconds(10);

    public static final Duration DEFAULT_REFRESH_RETRY_INTERVAL = Duration.ofSeconds(5);

    /** The upper bound of the default refresh lead time. */
    public static final Duration DEFAULT_MAX_REFRESH_LEAD_TIME = Duration.ofSeconds(60);

    public VcmpClientAuthentication {
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(answerTimeout, "answerTimeout");
        Objects.requireNonNull(failureHandler, "failureHandler");
        if (refreshRetryInterval == null) {
            refreshRetryInterval = DEFAULT_REFRESH_RETRY_INTERVAL;
        }
        if (authenticatedHandler == null) {
            authenticatedHandler = () -> {
            };
        }
        requireAtLeastMinInterval("answerTimeout", answerTimeout);
        requireAtLeastMinInterval("refreshRetryInterval", refreshRetryInterval);
        if (refreshLeadTime != null && refreshLeadTime.isNegative()) {
            throw new IllegalArgumentException("refreshLeadTime must not be negative.");
        }
    }

    /** @throws IllegalArgumentException if the duration is below {@link #MIN_INTERVAL}. */
    public static void requireAtLeastMinInterval(String name, Duration duration) {
        if (duration == null || duration.compareTo(MIN_INTERVAL) < 0) {
            throw new IllegalArgumentException(name + " must be at least " + MIN_INTERVAL.toMillis() + " ms, was "
                    + (duration != null ? duration.toMillis() + " ms" : "null") + ".");
        }
    }

    /** A refresh is never sent sooner than this after the ACK that scheduled it. */
    public static final Duration MIN_REFRESH_DELAY = Duration.ofSeconds(1);

    /**
     * The effective lead time before the expiry at which to refresh, for an {@code expiresIn} of the given
     * milliseconds: the configured one (default {@code min(60 s, 20 % of expiresIn)}), at most half of
     * {@code expiresIn}.
     */
    long refreshLeadMillis(long expiresInMillis) {
        val configured = refreshLeadTime != null
                ? refreshLeadTime.toMillis()
                : Math.min(DEFAULT_MAX_REFRESH_LEAD_TIME.toMillis(), expiresInMillis / 5);
        return Math.min(configured, expiresInMillis / 2);
    }

    /**
     * How long after the ACK to refresh: {@code expiresIn} minus the effective lead time, but never sooner than
     * {@link #MIN_REFRESH_DELAY}, so no lead time or tiny {@code expiresIn} can cause a tight refresh loop.
     */
    long refreshDelayMillis(long expiresInMillis) {
        return Math.max(MIN_REFRESH_DELAY.toMillis(), expiresInMillis - refreshLeadMillis(expiresInMillis));
    }
}
