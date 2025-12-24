package com.variocube.vcmp;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static com.variocube.vcmp.ObjectMapperHolder.OBJECT_MAPPER;
import static org.springframework.util.StringUtils.hasText;

@Slf4j
public class VcmpCallback<T> {

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

    public VcmpCallback<T> onAck(Runnable ack) {
        return onAck(ignoredResult -> ack.run());
    }

    public VcmpCallback<T> onAck(Consumer<T> ack) {
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

    public VcmpCallback<T> onNak(Runnable nak) {
        return onNak(ignoredProblem -> nak.run());
    }

    public VcmpCallback<T> onNak(Consumer<ProblemDetail> nak) {
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
     * Awaits an ACK or NAK response to the message. Returns on ACK, or throws an exception on NAK.
     * WARNING: this blocks the current thread, possibly indefinitely.
     * @deprecated Because this may block the current thread indefinitely.
     * use await(long timeout, TimeUnit unit) instead.
     */
    @Deprecated(forRemoval = true)
    public T await() throws ErrorResponseException {
        try {
            return toCompletableFuture().join();
        }
        catch (CompletionException e) {
            // extract error response exception from CompletionException
            if (e.getCause() instanceof ErrorResponseException errorResponseException) {
                throw errorResponseException;
            }
            throw e;
        }
    }

    /**
     * Awaits an ACK or NAK response to the message for at max `timeout` `unit`.
     * Returns on ACK, or throws an exception on NAK, or when the timeout elapsed.
     * @param timeout The timeout
     * @param unit The unit the timeout is specified in.
     * @throws InterruptedException
     * @throws ExecutionException
     * @throws TimeoutException
     */
    public T await(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        try {
            return toCompletableFuture().get(timeout, unit);
        }
        catch (ExecutionException e) {
            val cause = e.getCause();
            if (cause instanceof ErrorResponseException errorResponseException) {
                throw errorResponseException;
            }
            throw e;
        }
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
