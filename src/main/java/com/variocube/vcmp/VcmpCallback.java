package com.variocube.vcmp;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.variocube.vcmp.ObjectMapperHolder.OBJECT_MAPPER;
import static org.springframework.util.StringUtils.hasText;

@Slf4j
public class VcmpCallback<T> {

    private static final int DEFAULT_TIMEOUT_SECONDS = 20;

    private enum State {
        PENDING,
        COMPLETED,
        FAILED,
    }

    private volatile State state = State.PENDING;

    private T result;
    private ProblemDetail problemDetail;

    private Consumer<T> ack;
    private Consumer<ProblemDetail> nak;

    private Class<T> resultClass;

    public VcmpCallback(Class<T> resultClass) {
        this.resultClass = resultClass;
    }

    public VcmpCallback() {
    }

    VcmpCallback<T> onAck(Runnable ack) {
        return onAck(ignoredResult -> ack.run());
    }

    VcmpCallback<T> onAck(Consumer<T> ack) {
        // set the ACK handler only if execution is pending
        if (state == State.PENDING) {
            if (this.ack != null) {
                throw new IllegalStateException("Only one onAck handler is supported.");
            }
            this.ack = ack;
        }
        // Execute the handler directly if the execution completed
        // Keep this as separate `if` to avoid race condition if `notifyAck` was called
        // during execution of the code above
        if (state == State.COMPLETED) {
            ack.accept(this.result);
        }
        return this;
    }

    VcmpCallback<T> onNak(Runnable nak) {
        return onNak(ignoredProblem -> nak.run());
    }

    VcmpCallback<T> onNak(Consumer<ProblemDetail> nak) {
        // set the NAK handler only if execution is pending
        if (state == State.PENDING) {
            if (this.nak != null) {
                throw new IllegalStateException("Only one onNak handler is supported.");
            }
            this.nak = nak;
        }
        // Execute the handler directly if the execution failed
        // Keep this as separate `if` to avoid race condition if `notifyNak` was called
        // during execution of the code above
        if (state == State.FAILED) {
            nak.accept(this.problemDetail);
        }
        return this;
    }

    void notifyAck(T result) {
        this.result = result;
        this.state = State.COMPLETED;
        if (this.ack != null) {
            log.debug("Invoking ACK handler.");
            this.ack.accept(result);
        }
    }

    void notifyAckRaw(String resultPayload) {
        notifyAck(parseResult(resultPayload));
    }

    void notifyNak(ProblemDetail problemDetail) {
        this.problemDetail = problemDetail;
        this.state = State.FAILED;
        if (this.nak != null) {
            log.debug("Invoking NAK handler.");
            this.nak.accept(problemDetail);
        }
    }

    public CompletableFuture<T> toCompletableFuture() {
        CompletableFuture<T> completableFuture = new CompletableFuture<>();
        this.onAck(completableFuture::complete);
        this.onNak(errorProblemDetail -> {
            val exception = new ErrorResponseException(HttpStatusCode.valueOf(errorProblemDetail.getStatus()), errorProblemDetail, null);
            completableFuture.completeExceptionally(exception);
        });
        return completableFuture;
    }

    /**
     * Awaits a result or an error to the message using the default timeout of 20 seconds.
     *
     * @throws ErrorResponseException if the operation fails.
     * @return The result of the operation.
     */
    public T await() {
        return await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Awaits a result or an error to the message for at most `seconds` seconds.
     * @param seconds The timeout in seconds.
     * @throws ErrorResponseException if the operation fails.
     * @return The result of the operation.
     */
    public T awaitSeconds(int seconds) {
        return await(seconds, TimeUnit.SECONDS);
    }

    /**
     * Awaits a result or an error to the message for the specified timeout.
     * @param timeout The timeout
     * @param unit The unit the timeout is specified in.
     * @throws ErrorResponseException if the operation fails.
     * @return The result of the operation.
     */
    public T await(long timeout, TimeUnit unit) {
        try {
            return toCompletableFuture().get(timeout, unit);
        }
        catch (ExecutionException e) {
            val cause = e.getCause();
            if (cause instanceof ErrorResponseException errorResponseException) {
                throw errorResponseException;
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error while waiting for response.", cause);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT, "Interrupted while waiting for response.");
        }
        catch (TimeoutException e) {
            throw new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT, "Timeout while waiting for response.");
        }
    }

    /**
     * Maps the result of the callback to another value.
     * @return A new callback with the mapped result.
     */
    public <U> VcmpCallback<U> map(Function<T, U> mapper) {
        val callback = new VcmpCallback<U>();
        this.onAck(originalResult -> callback.notifyAck(mapper.apply(originalResult)));
        this.onNak(callback::notifyNak);
        return callback;
    }

    /**
     * Maps the result of the callback to another value and invokes a side effect on NAK.
     * The error is propagated to the returned callback unchanged.
     * @return A new callback with the mapped result.
     */
    public <U> VcmpCallback<U> map(Function<T, U> mapper, Consumer<ProblemDetail> nakHandler) {
        val callback = new VcmpCallback<U>();
        this.onAck(originalResult -> callback.notifyAck(mapper.apply(originalResult)));
        this.onNak(error -> {
            nakHandler.accept(error);
            callback.notifyNak(error);
        });
        return callback;
    }

    /**
     * Registers a side effect to be invoked on ACK without consuming the terminal handler slot.
     * The result is propagated to the returned callback unchanged.
     * @return A new callback that fires the side effect and forwards the result.
     */
    public VcmpCallback<T> peekAck(Consumer<T> sideEffect) {
        return this.map(result -> {
            sideEffect.accept(result);
            return result;
        });
    }

    /**
     * Registers a side effect to be invoked on ACK without consuming the terminal handler slot.
     * The result is propagated to the returned callback unchanged.
     * @return A new callback that fires the side effect and forwards the result.
     */
    public VcmpCallback<T> peekAck(Runnable sideEffect) {
        return peekAck(ignoredResult -> sideEffect.run());
    }

    /**
     * Registers a side effect to be invoked on NAK without consuming the terminal handler slot.
     * The error is propagated to the returned callback unchanged.
     * @return A new callback that fires the side effect and forwards the error.
     */
    public VcmpCallback<T> peekNak(Consumer<ProblemDetail> sideEffect) {
        return this.map(Function.identity(), sideEffect);
    }

    /**
     * Registers a side effect to be invoked on NAK without consuming the terminal handler slot.
     * The error is propagated to the returned callback unchanged.
     * @return A new callback that fires the side effect and forwards the error.
     */
    public VcmpCallback<T> peekNak(Runnable sideEffect) {
        return peekNak(ignoredProblem -> sideEffect.run());
    }

    public static VcmpCallback<Void> completed() {
        val callback = new VcmpCallback<Void>();
        callback.notifyAck(null);
        return callback;
    }

    public static <T> VcmpCallback<T> completed(T result) {
        val callback = new VcmpCallback<T>();
        callback.notifyAck(result);
        return callback;
    }

    public static <T> VcmpCallback<T> failed() {
        val callback = new VcmpCallback<T>();
        callback.notifyNak(ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        return callback;
    }

    public static <T> VcmpCallback<T> failed(ProblemDetail problemDetail) {
        val callback = new VcmpCallback<T>();
        callback.notifyNak(problemDetail);
        return callback;
    }

    @SafeVarargs
    public static <T> VcmpCallback<Collection<T>> all(VcmpCallback<T>... callbacks) {
        return all(Arrays.asList(callbacks));
    }

    public static <T> VcmpCallback<Collection<T>> all(Collection<VcmpCallback<T>> callbacks) {
        val combined = new VcmpCallback<Collection<T>>();
        val results = new ArrayList<T>();
        for (val callback : callbacks) {
            callback.onAck(result -> {
                results.add(result);
                if (results.size() == callbacks.size()) {
                    combined.notifyAck(results);
                }
            });
            callback.onNak(combined::notifyNak);
        }
        return combined;
    }

    public static <T> VcmpCallback<T> any(Collection<VcmpCallback<T>> callbacks) {
        val combined = new VcmpCallback<T>();
        val errors = new AtomicInteger();
        for (val callback : callbacks) {
            callback.onAck(combined::notifyAck);
            callback.onNak(error -> {
                if (errors.incrementAndGet() == callbacks.size()) {
                    combined.notifyNak(error);
                }
            });
        }
        return combined;
    }

    private T parseResult(String payload) {
        if (!hasText(payload)) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(payload, resultClass);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse result from payload: {}", payload, e);
            return null;
        }
    }

}
