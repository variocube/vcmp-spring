package com.variocube.vcmp.client;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the method of a client that supplies the credential for session authentication ({@code AUT}), for an endpoint
 * that requires it ({@code @VcmpEndpoint(authenticated = true)}).
 * <p>
 * Takes no parameters and returns the credential, or a {@code CompletionStage} of it: an object that serializes to a
 * JSON object of the shape the endpoint expects (e.g. a record, or {@code Map.of("token", token)}). It is called on
 * every (re)connect and on every re-authentication ({@link BasicVcmpClient#reauthenticate()}), so it can return a fresh
 * token each time. The client schedules no re-authentication: the application calls {@code reauthenticate()} when its
 * token was refreshed.
 * <p>
 * With it, the client sends the credential first on every session and reports it connected
 * ({@code @VcmpSessionConnected}) only after the server's {@code ACK}; until then nothing else can be sent. A
 * {@code NAK}, or no answer within the answer timeout, is an authentication error (see
 * {@link VcmpAuthenticationFailed}); the session is closed and reconnects as usual, with a fresh credential.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface VcmpCredential {
}
