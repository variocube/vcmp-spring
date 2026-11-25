package com.variocube.vcmp;

import org.springframework.http.HttpHeaders;

import java.net.URI;
import java.security.Principal;
import java.util.Map;

/**
 * The handshake request of a session, passed to a {@link VcmpHandshakeAuthenticator}.
 *
 * @param uri           The request URI, including the query.
 * @param headers       The request headers.
 * @param pathVariables The URI template variables of the endpoint's path, e.g. {@code token} for
 *                      {@code /vcmp/{token}}; empty if it has none.
 * @param principal     The principal of the handshake request (e.g. from Spring Security), or {@code null}.
 */
public record VcmpHandshake(URI uri, HttpHeaders headers, Map<String, String> pathVariables, Principal principal) {
}
