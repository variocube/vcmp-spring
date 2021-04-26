package com.variocube.vcmp.echo;

import com.variocube.vcmp.VcmpTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;

import static org.awaitility.Awaitility.await;

public class EchoTest extends VcmpTestBase {

    @Autowired
    private EchoClient client;

    @Test
    public void canEcho() throws IOException {
        await().until(client::isConnected);
        client.echoResponse = "foo";
        client.send(new EchoRequest("bar"));
        await().until(() -> client.echoResponse.equals("bar"));
    }


}
