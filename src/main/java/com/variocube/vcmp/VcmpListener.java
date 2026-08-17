package com.variocube.vcmp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface VcmpListener {

    /**
     * Retry the listener with backoff on transient failure before NAKing (variocube/center#450).
     * <p>
     * Only safe for idempotent listeners — typically those whose work happens in a single
     * transaction that leaves no partial effects on rollback. With retry enabled, the listener
     * may be invoked more than once for the same message. Failures that resolve to a deliberate
     * error status ({@code ErrorResponseException} or an exception annotated with
     * {@code @ResponseStatus}) are never retried.
     */
    boolean retry() default false;
}
