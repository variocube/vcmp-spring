package com.variocube.vcmp.client;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the method of a client that is told when session authentication fails: the server's {@code NAK} to an
 * {@code AUT} (400 the endpoint does not use authentication, 401 invalid credential, 403 not allowed), no answer within
 * the answer timeout (408, e.g. a server that does not know {@code AUT}), or a failing {@link VcmpCredential} method.
 * <p>
 * Takes the {@code ProblemDetail} (optional). Called on a VCMP worker thread.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface VcmpAuthenticationFailed {
}
