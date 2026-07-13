package com.variocube.vcmp.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejects websocket handshakes until the application is fully started.
 *
 * Tomcat opens its port before {@code ApplicationReadyEvent} fires, so on a restart a fleet of
 * VCMP clients reconnects while startup is still in progress. The work done per connect
 * (handshake authentication, connect handlers) can starve resources — e.g. the DB pool — that
 * startup itself still needs (variocube/center#427). Answering handshakes with 503 until ready
 * is safe: VCMP clients reconnect with retry, so the startup window is indistinguishable from
 * the port not being open yet.
 *
 * Runs at highest precedence so rejected handshakes don't even reach the security filter chain
 * and its potentially DB-backed authentication.
 *
 * Disable with {@code vcmp.server.ready-gate.enabled=false}.
 */
@Slf4j
public final class VcmpReadyGateFilter extends OncePerRequestFilter implements Ordered {

    private volatile boolean ready = false;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void handleApplicationReady() {
        log.info("Application ready, accepting websocket handshakes.");
        this.ready = true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!ready && isWebSocketHandshake(request)) {
            log.info("Rejecting websocket handshake for {} before application ready.", request.getRequestURI());
            response.setHeader("Retry-After", "5");
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "Starting up");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isWebSocketHandshake(HttpServletRequest request) {
        String upgrade = request.getHeader("Upgrade");
        return upgrade != null && upgrade.equalsIgnoreCase("websocket");
    }
}
