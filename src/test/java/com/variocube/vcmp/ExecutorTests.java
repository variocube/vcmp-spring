package com.variocube.vcmp;


import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.*;

import java.io.Console;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@TestMethodOrder(value = MethodOrderer.MethodName.class)
public class ExecutorTests {
    int cnt_no_wait = 0;
    int cnt = 0;
    @Test
    public void testHandleMessage_0_submit_with_wait_no_wait() {
        var executor = Executor.getExecutor();
        for (int i=0; i< 100; i++) {
            executor.submit(() -> {
                log.info("doing nothing but logging.");
                cnt_no_wait+=1;
            });
           // wait(1, TimeUnit.MILLISECONDS);
        }
        wait(3, TimeUnit.SECONDS);
        assertEquals(100, cnt_no_wait);
        assertEquals(0, executor.getCachedPoolActiveCount());
    }
    @Test
    public void testHandleMessage_0_submit_with_wait() {
        var executor = Executor.getExecutor();
        for (int i=0; i< 11; i++) {
            executor.submit(() -> {
                log.info("doing nothing but logging.");
                cnt+=1;
            });
            wait(1, TimeUnit.SECONDS);
        }
        wait(3, TimeUnit.SECONDS);
        assertEquals(11, cnt);
        assertEquals(0, executor.getCachedPoolActiveCount());
    }

    private void wait(int intervall, TimeUnit unit ) {
        await()
                .timeout(intervall+1, unit)
                .pollDelay(intervall, unit)
                .untilAsserted(() -> assertTrue(true));
    }
}
