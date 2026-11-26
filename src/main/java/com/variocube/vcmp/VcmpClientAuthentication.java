package com.variocube.vcmp;

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
 * The client schedules no re-authentication: the application triggers it ({@code reauthenticate()}), typically from
 * its token-refreshed callback.
 * <p>
 * An answer timeout below {@link #MIN_ANSWER_TIMEOUT} is rejected with an {@code IllegalArgumentException} (not
 * clamped).
 *
 * @param credentials          Supplies the credential, called on every (re)connect and every re-authentication.
 *                             Completes with an object that serializes to a JSON object (e.g. a record or a
 *                             {@code Map}).
 * @param answerTimeout        How long an authentication attempt may take, from requesting the credential to the
 *                             server's answer to the {@code AUT}; at least 1 s.
 * @param failureHandler       Called with the problem whenever an authentication fails ({@code NAK}, answer timeout or
 *                             a failing credential supplier), on connect and on re-authentication.
 * @param authenticatedHandler Called whenever an authentication succeeded (first or re-authentication); {@code null}
 *                             for none.
 */
public record VcmpClientAuthentication(Supplier<CompletionStage<?>> credentials, Duration answerTimeout,
        Consumer<ProblemDetail> failureHandler, Runnable authenticatedHandler) {

    /** The least answer timeout. */
    public static final Duration MIN_ANSWER_TIMEOUT = Duration.ofSeconds(1);

    public static final Duration DEFAULT_ANSWER_TIMEOUT = Duration.ofSeconds(10);

    public VcmpClientAuthentication {
        Objects.requireNonNull(credentials, "credentials");
        Objects.requireNonNull(failureHandler, "failureHandler");
        if (authenticatedHandler == null) {
            authenticatedHandler = () -> {
            };
        }
        requireValidAnswerTimeout(answerTimeout);
    }

    /** @throws IllegalArgumentException if the answer timeout is {@code null} or below {@link #MIN_ANSWER_TIMEOUT}. */
    public static void requireValidAnswerTimeout(Duration answerTimeout) {
        if (answerTimeout == null || answerTimeout.compareTo(MIN_ANSWER_TIMEOUT) < 0) {
            throw new IllegalArgumentException("The answer timeout must be at least " + MIN_ANSWER_TIMEOUT.toMillis()
                    + " ms, was " + (answerTimeout != null ? answerTimeout.toMillis() + " ms" : "null") + ".");
        }
    }
}
