package com.variocube.vcmp.client;

import com.variocube.vcmp.ClassUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextStoppedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Async;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

@Configuration
@Slf4j
public class VcmpClientAutoConfiguration {

    private final ArrayList<VcmpConnectionManager> managers = new ArrayList<>();

    public VcmpClientAutoConfiguration(ApplicationContext applicationContext, Environment environment, TaskScheduler taskScheduler, AsyncTaskExecutor asyncTaskExecutor) {
        log.info("Looking up VCMP clients in application context.");
        Map<String, Object> clients = applicationContext.getBeansWithAnnotation(VcmpClient.class);
        for (Object client : clients.values()) {
            Class<?> targetClass = ClassUtils.getTargetClass(client);
            VcmpClient vcmpClient = targetClass.getAnnotation(VcmpClient.class);
            String url = environment.resolveRequiredPlaceholders(vcmpClient.url());
            log.info("Found VCMP client `{}` connecting to {}", targetClass.getSimpleName(), url);
            VcmpConnectionManager manager = new VcmpConnectionManager(taskScheduler, asyncTaskExecutor, client, url);
            managers.add(manager);
            manager.setReconnectTimeout(vcmpClient.reconnect());
        }
    }

    @EventListener
    @Async
    public void handleApplicationReady(ApplicationReadyEvent event) {
        this.managers.forEach(VcmpConnectionManager::start);
    }

    @EventListener
    public void handleContextStopped(ContextStoppedEvent event) {
        stop();
    }

    @EventListener
    public void handleContextClosed(ContextClosedEvent event) {
        stop();
    }

    private void stop() {
        for (VcmpConnectionManager manager : this.managers) {
            try {
                manager.stop();
            } catch (IOException e) {
                log.error("Error while stopping connection manager", e);
            }
        }
    }

}
