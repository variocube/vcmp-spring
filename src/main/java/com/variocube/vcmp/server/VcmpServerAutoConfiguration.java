package com.variocube.vcmp.server;

import com.variocube.vcmp.ClassUtils;
import com.variocube.vcmp.VcmpHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.Map;

@Configuration
@Conditional(VcmpEndpointCondition.class)
@RequiredArgsConstructor
@Slf4j
@EnableWebSocket
public class VcmpServerAutoConfiguration implements WebSocketConfigurer {

    private final ApplicationContext applicationContext;
    private final Environment environment;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        Map<String, Object> endpointBeans = applicationContext.getBeansWithAnnotation(VcmpEndpoint.class);
        for (Object endpoint : endpointBeans.values()) {
            Class<?> endpointClass = ClassUtils.getTargetClass(endpoint);

            VcmpEndpoint vcmpEndpoint = endpointClass.getAnnotation(VcmpEndpoint.class);
            String path = environment.resolveRequiredPlaceholders(vcmpEndpoint.path());
            if (StringUtils.hasText(path)) {
                log.info("Registering endpoint {} with {}", path, endpoint.getClass().getSimpleName());
                registry.addHandler(new VcmpHandler(endpoint), path)
                        .setAllowedOrigins("*");
            }
        }
    }

}
