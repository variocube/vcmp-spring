package com.variocube.vcmp.client;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Supplies headers immediately before each connection attempt, including automatic reconnects.
 * The method receives a fresh HttpHeaders instance; it must not retain or asynchronously mutate it.
 * Implementations should be quick and may refresh expiring credentials here. A failure prevents the
 * handshake and is retried through the normal reconnect policy.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface VcmpHttpHeaders {
}
