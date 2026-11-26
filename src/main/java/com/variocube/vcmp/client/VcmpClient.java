package com.variocube.vcmp.client;

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
public @interface VcmpClient {
    /**
     * The value may indicate a suggestion for a logical component name,
     * to be turned into a Spring bean in case of an autodetected component.
     * @return the suggested component name, if any (or empty String otherwise)
     */
    @AliasFor(annotation = Component.class)
    String value() default "";

    String url();

    long reconnectMinSeconds() default VcmpConnectionManager.DEFAULT_RECONNECT_TIMEOUT_MIN_SECONDS;

    long reconnectMaxSeconds() default VcmpConnectionManager.DEFAULT_RECONNECT_TIMEOUT_MAX_SECONDS;

    long disconnectSeconds() default VcmpConnectionManager.DEFAULT_DISCONNECT_TIMEOUT_SECONDS;

    /**
     * With a {@link VcmpCredential}: how long an authentication attempt may take, from requesting the credential to the
     * server's answer to the {@code AUT}, in milliseconds; at least 1000.
     */
    long authTimeoutMillis() default 10_000;
}
