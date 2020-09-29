package com.variocube.vcmp.size;

import com.variocube.vcmp.VcmpTestBase;
import lombok.val;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class SizeTest extends VcmpTestBase {

    @Autowired
    private SizeClient client;

    @Autowired
    private SizeEndpoint endpoint;

    private SecureRandom random = new SecureRandom();

    @Test
    public void canHandleBigMessages() throws IOException {
        await().until(client::isConnected);

        verify(512 * 1024);
        verify(1024 * 1024);
        verify(16 * 1024 * 1024);
    }

    private void verify(int length) throws IOException {
        val bytes = generateRandomBytes(length);
        client.send(new PotentiallyHugeMessage(bytes)).await();
        await().until(() -> client.data.length == length);
        assertThat(client.data).isEqualTo(bytes);
    }

    private byte[] generateRandomBytes(int length) {
        val bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }
}
