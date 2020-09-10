package com.variocube.vcmp.size;

import com.variocube.vcmp.VcmpTestBase;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.Arrays;

import static org.awaitility.Awaitility.await;

public class SizeTest extends VcmpTestBase {

    @Autowired
    private SizeClient client;

    @Autowired
    private SizeEndpoint endpoint;

    @Test
    public void canHandleBigMessages() throws IOException {
        await().until(client::isConnected);

        verifyBytes(512 * 1024);
        verifyBytes(1024 * 1024);
        verifyBytes(16 * 1024 * 1024);
    }

    private void verifyBytes(int length) throws IOException {
        byte[] data = new byte[length];
        Arrays.fill(data, (byte)42);
        client.send(new PotentiallyHugeMessage(data));
        await().until(() -> endpoint.data.length == length);
    }


}
