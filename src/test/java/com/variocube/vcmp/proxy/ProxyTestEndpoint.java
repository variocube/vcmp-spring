package com.variocube.vcmp.proxy;

import com.variocube.vcmp.server.VcmpEndpoint;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

@VcmpEndpoint(path = "/proxy")
public class ProxyTestEndpoint {

    static class TestEvent {}

    @Async
    @EventListener
    public void handleTestEvent(TestEvent testEvent) {
    }

}
