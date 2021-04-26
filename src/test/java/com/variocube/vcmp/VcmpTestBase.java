package com.variocube.vcmp;

import org.awaitility.Awaitility;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

@SpringBootTest(value = {
        "server.port=6969",
        "logging.level.com.variocube.vcmp=TRACE"
}, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
public abstract class VcmpTestBase {

    public static final String BASE_URL = "ws://localhost:6969";

    static {
        Awaitility.setDefaultTimeout(3000, TimeUnit.MILLISECONDS);
    }

}
