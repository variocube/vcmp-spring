package com.variocube.vcmp.echo2;

import com.variocube.vcmp.VcmpTestBase;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class Echo2Test extends VcmpTestBase {

    @Autowired
    private Echo2Client client;

    @Test
    void canEcho() throws IOException, ExecutionException, InterruptedException, TimeoutException {
        await().until(client::isConnected);
        val response = client.send(new EchoRequest("bar"), EchoResponse.class)
                        .await(100, TimeUnit.MILLISECONDS);
        assertThat(response.getMessage()).isEqualTo("bar");
    }
}
