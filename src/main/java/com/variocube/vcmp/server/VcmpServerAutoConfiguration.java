package com.variocube.vcmp.server;

import com.variocube.vcmp.ClassUtils;
import com.variocube.vcmp.VcmpHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Map;
import java.util.concurrent.Semaphore;

@Configuration
@Conditional(VcmpEndpointCondition.class)
@RequiredArgsConstructor
@Slf4j
@EnableWebSocket
public class VcmpServerAutoConfiguration implements WebSocketConfigurer {

    static final int DEFAULT_CONNECT_CONCURRENCY = 8;

    private final ApplicationContext applicationContext;
    private final Environment environment;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Bound connect-handler concurrency across all endpoints of this application:
        // they compete for the same resources (DB pool), so the bound must be shared.
        Semaphore connectThrottle = createConnectThrottle();

        int listenerRetryAttempts = environment.getProperty("vcmp.server.listener-retry.attempts",
                Integer.class, VcmpHandler.DEFAULT_LISTENER_RETRY_ATTEMPTS);
        long listenerRetryInitialDelayMs = environment.getProperty("vcmp.server.listener-retry.initial-delay-ms",
                Long.class, VcmpHandler.DEFAULT_LISTENER_RETRY_INITIAL_DELAY_MS);
        if (listenerRetryAttempts <= 1) {
            log.info("VCMP listener retry is disabled.");
        }
        else {
            log.info("Retrying opted-in VCMP listeners up to {} attempts, initial delay {} ms.",
                    listenerRetryAttempts, listenerRetryInitialDelayMs);
        }

        Map<String, Object> endpointBeans = applicationContext.getBeansWithAnnotation(VcmpEndpoint.class);
        for (Object endpoint : endpointBeans.values()) {
            Class<?> endpointClass = ClassUtils.getTargetClass(endpoint);

            VcmpEndpoint vcmpEndpoint = endpointClass.getAnnotation(VcmpEndpoint.class);
            String path = environment.resolveRequiredPlaceholders(vcmpEndpoint.path());
            if (StringUtils.hasText(path)) {
                log.info("Registering endpoint {} with {}", path, endpoint.getClass().getSimpleName());
                VcmpHandler handler = new VcmpHandler(endpoint);
                handler.setConnectThrottle(connectThrottle);
                handler.setListenerRetryAttempts(listenerRetryAttempts);
                handler.setListenerRetryInitialDelayMs(listenerRetryInitialDelayMs);
                registry.addHandler(handler, path)
                        .setAllowedOrigins("*");
            }
        }
    }

    private Semaphore createConnectThrottle() {
        int connectConcurrency = environment.getProperty("vcmp.server.connect-concurrency",
                Integer.class, DEFAULT_CONNECT_CONCURRENCY);
        if (connectConcurrency <= 0) {
            log.info("VCMP connect throttling is disabled.");
            return null;
        }
        log.info("Bounding concurrent VCMP connect handlers to {}.", connectConcurrency);
        return new Semaphore(connectConcurrency);
    }

    @Bean
    @ConditionalOnProperty(name = "vcmp.server.ready-gate.enabled", havingValue = "true", matchIfMissing = true)
    public VcmpReadyGateFilter vcmpReadyGateFilter() {
        return new VcmpReadyGateFilter();
    }

}
