package com.variocube.vcmp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the method of an authenticated endpoint ({@code @VcmpEndpoint(authenticated = true)}) that authenticates a
 * session from the credential of an {@code AUT} frame.
 * <p>
 * Parameters: a {@link VcmpSession} (optional) and the credential (optional): the {@code AUT} payload, a JSON object,
 * converted to the parameter's type (e.g. a record, {@code Map<String, String>} or {@code JsonNode}). A payload that is
 * not a JSON object, or does not convert, is answered with {@code NAK} 400.
 * <p>
 * Returns the {@link VcmpAuthentication}, or {@code null} for an invalid credential ({@code NAK} 401). A thrown
 * exception is mapped like a listener's ({@code ResponseStatusException}, {@code @ResponseStatus}), e.g. to 401 or 403
 * (valid credential, but not allowed); any other exception is a {@code NAK} 500.
 * <p>
 * The method is called for the first {@code AUT} and for every re-authentication. A re-authentication must yield a
 * principal {@linkplain Object#equals equal} to the session's ({@link VcmpSession#getPrincipal()}), otherwise the
 * session is closed. An endpoint with its own notion of "same principal" compares it itself and returns the session's
 * current principal.
 * <p>
 * The credential is never logged by VCMP; don't log it here either.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface VcmpAuthenticator {
}
