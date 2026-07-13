package com.variocube.vcmp;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConnectThrottleTest {

    static class Target {
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxRunning = new AtomicInteger();
        final CountDownLatch done = new CountDownLatch(CONNECTS);

        @VcmpSessionConnected
        public void handleConnected(VcmpSession session) throws InterruptedException {
            int now = running.incrementAndGet();
            maxRunning.accumulateAndGet(now, Math::max);
            Thread.sleep(20);
            running.decrementAndGet();
            done.countDown();
        }
    }

    static final int CONNECTS = 10;

    @Test
    void boundsConnectHandlerConcurrency() throws Exception {
        Target target = new Target();
        VcmpHandler handler = new VcmpHandler(target);
        handler.setConnectThrottle(new Semaphore(2));

        for (int i = 0; i < CONNECTS; i++) {
            handler.afterConnectionEstablished(mockSession("session-" + i));
        }

        assertThat(target.done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(target.maxRunning.get()).isLessThanOrEqualTo(2);
    }

    @Test
    void runsUnthrottledWithoutSemaphore() throws Exception {
        Target target = new Target();
        VcmpHandler handler = new VcmpHandler(target);

        for (int i = 0; i < CONNECTS; i++) {
            handler.afterConnectionEstablished(mockSession("session-" + i));
        }

        assertThat(target.done.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private static WebSocketSession mockSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        return session;
    }
}
