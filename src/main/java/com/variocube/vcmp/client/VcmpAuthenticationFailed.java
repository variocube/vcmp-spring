package com.variocube.vcmp.client;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the method of a client that is told when session authentication fails: the server's {@code NAK} to an
 * {@code AUT} with the server's status (400 the endpoint does not use authentication, 401 invalid credential, 403 not
 * allowed, 500, 503), or a local problem (never on the wire): 500 the {@link VcmpCredential} method failed, 504 it
 * did not complete or a sent {@code AUT} got no answer within the answer timeout (e.g. a server that does not know
 * {@code AUT}), 503 the session closed while the {@code AUT} was pending;
 * on connect and on a re-authentication ({@link BasicVcmpClient#reauthenticate()}, whose future fails with the same
 * problem).
 * <p>
 * Takes the {@code ProblemDetail} (optional). Called on a VCMP worker thread.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface VcmpAuthenticationFailed {
}
