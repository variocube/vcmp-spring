package com.variocube.vcmp;

import org.awaitility.Awaitility;
import org.junit.runner.RunWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.concurrent.TimeUnit;

@RunWith(SpringRunner.class)
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
