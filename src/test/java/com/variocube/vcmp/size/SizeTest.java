package com.variocube.vcmp.size;

import com.variocube.vcmp.VcmpTestBase;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.AsyncTaskExecutor;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class SizeTest extends VcmpTestBase {

    @Autowired
    private SizeClient client;

    @Autowired
    private SizeEndpoint endpoint;

    @Autowired
    private AsyncTaskExecutor asyncTaskExecutor;

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

    @Test
    public void canHandleConcurrentTransfer() throws ExecutionException, InterruptedException {
        await().until(client::isConnected);

        val threadPool = Executors.newWorkStealingPool(2);
        val fut1 = threadPool.submit(() -> client.send(new PotentiallyHugeMessage(generateRandomBytes(512 * 1024))));
        val fut2 = threadPool.submit(() -> client.send(new PotentiallyHugeMessage(generateRandomBytes(512 * 1024))));
        fut1.get();
        fut2.get();
    }
}
