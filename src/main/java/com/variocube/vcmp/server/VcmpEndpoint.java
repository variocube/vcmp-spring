package com.variocube.vcmp.server;

import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Controller
public @interface VcmpEndpoint {
    /**
     * The value may indicate a suggestion for a logical component name,
     * to be turned into a Spring bean in case of an autodetected component.
     * @return the suggested component name, if any (or empty String otherwise)
     */
    @AliasFor(annotation = Component.class)
    String value() default "";

    String path();

    /**
     * Requires every session to authenticate with an {@code AUT} frame (or, in transition mode, by its handshake)
     * before anything else: the endpoint must have a {@link com.variocube.vcmp.VcmpAuthenticator} method and may have
     * a {@link com.variocube.vcmp.VcmpHandshakeAuthenticator}. Until a session is authenticated, only {@code HBT} and
     * {@code AUT} frames are accepted and {@code @VcmpSessionConnected} does not fire.
     */
    boolean authenticated() default false;

    /**
     * How long a session of an {@link #authenticated()} endpoint has to authenticate after connecting, in milliseconds,
     * before it is closed (1008).
     */
    long authTimeoutMillis() default DEFAULT_AUTH_TIMEOUT_MILLIS;

    long DEFAULT_AUTH_TIMEOUT_MILLIS = 10_000;
}
