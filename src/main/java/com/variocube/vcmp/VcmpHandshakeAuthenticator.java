package com.variocube.vcmp;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the method of an authenticated endpoint ({@code @VcmpEndpoint(authenticated = true)}) that authenticates a
 * session from its handshake, for old clients that still send their credential there (query parameter, path variable,
 * header). Meant for the migration to {@code AUT} only: a handshake credential is in the URL with all its problems.
 * <p>
 * Parameter: the {@link VcmpHandshake}. It runs once, when the session is established, before any frame is handled.
 * <ul>
 *     <li>Returns a {@link VcmpAuthentication}: the session is authenticated right away, without {@code AUT}. Return
 *     no expiry unless there is a reason: an old client can't re-authenticate, so it would be disconnected.</li>
 *     <li>Returns {@code null}: the handshake carries no credential; the session waits for an {@code AUT}.</li>
 *     <li>Throws: the handshake credential is invalid; the session is closed (1008), without a fallback to
 *     {@code AUT}.</li>
 * </ul>
 * VCMP logs how many sessions were authenticated by handshake and by {@code AUT}, so the endpoint knows when the last
 * old client is gone and this method can be removed.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface VcmpHandshakeAuthenticator {
}
