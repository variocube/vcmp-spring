package com.variocube.vcmp.ping;

import com.variocube.vcmp.VcmpTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;

import static org.awaitility.Awaitility.await;

public class PingTest extends VcmpTestBase {

    @Autowired
    private PingEndpoint pingEndpoint;

    @Autowired
    private PingClient pingClient;

    @Test
    public void canReceiveHeartbeat() throws IOException {
        await().until(pingClient::isConnected);
        pingClient.initiateHeartbeat(100);
        await().until(() -> pingClient.getHeartbeatReceivedCount() > 5);
    }
}
