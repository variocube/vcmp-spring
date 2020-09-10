package com.variocube.vcmp.connect;

import com.variocube.vcmp.VcmpTestBase;
import com.variocube.vcmp.client.VcmpConnectionManager;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.TaskScheduler;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Slf4j
public class ConnectTest extends VcmpTestBase {

    @Autowired
    private ConnectClient client;

    @Autowired
    private ConnectEndpoint endpoint;

    @Autowired
    private TaskScheduler taskScheduler;

    @Autowired
    private AsyncTaskExecutor asyncTaskExecutor;

    @Test
    public void canSend() throws IOException {
        await().until(client::isConnected);
        endpoint.foo = "foo";
        client.send(new ChangeFooMessage("bar"));
        await().until(() -> endpoint.foo.equals("bar"));
    }

    @Test
    public void canAck() throws IOException {
        await().until(client::isConnected);
        AtomicBoolean ack = new AtomicBoolean(false);
        client.send(new ChangeFooMessage("Hi there!"), () -> ack.set(true));
        await().untilTrue(ack);
    }

    @Test
    public void handleSessionConnectedCalled() {
        await().until(client::isConnected);
        assertThat(client.handleSessionConnectedCalled).isTrue();
    }

    @Test
    public void nakOnNotImplementedMessage() throws IOException {
        await().until(client::isConnected);
        AtomicBoolean nak = new AtomicBoolean(false);
        client.send(new NotImplementedMessage("foo"), null, () -> nak.set(true));
        await().untilTrue(nak);
    }

    @Test
    public void canDisconnect() throws IOException {
        // manually create additional client
        try (VcmpConnectionManager connectionManager = new VcmpConnectionManager(taskScheduler, asyncTaskExecutor, new ConnectClient(), ConnectClient.URL)) {
            // after start, we must have 2 connections
            connectionManager.start();
            await().until(() -> endpoint.getSessionPool().getSessionCount() == 2);

            // after stop, we must go back to 1 connection
            connectionManager.stop();
            await().until(() -> endpoint.getSessionPool().getSessionCount() == 1);
        }

    }

    @Test
    public void canHandleMissingEndpoint() throws IOException {
        try (VcmpConnectionManager connectionManager = new VcmpConnectionManager(taskScheduler, asyncTaskExecutor, new ConnectClient(), "ws://localhost:12345/non/existing/endpoint")) {
            connectionManager.start();

            await().until(() -> connectionManager.getConnectionError() != null);
        }
    }

    @Test
    public void canReconnect() throws IOException {
        ConnectClient reconnectClient = new ConnectClient();
        try (VcmpConnectionManager connectionManager = new VcmpConnectionManager(taskScheduler, asyncTaskExecutor, reconnectClient, ConnectClient.URL)) {
            // set reconnect timeout to 300 ms, so we can catch the disconnected state when polling
            connectionManager.setReconnectTimeout(300);

            // start the connection
            connectionManager.start();
            await().until(reconnectClient::isConnected);

            // send close message, the endpoint will in turn close the connection
            reconnectClient.send(new Close());
            await().until(() -> !reconnectClient.isConnected());
            log.info("reconnectClient.isConnected: {}", reconnectClient.isConnected());

            // now the reconnect should happen automatically
            await().until(reconnectClient::isConnected);
        }
    }

    @Test
    public void canSendBase64EncodedByteArray() throws IOException {
        byte[] bytes = new byte[1024];
        Arrays.fill(bytes, (byte)42);

        await().until(client::isConnected);
        client.send(new Base64Message(bytes));
        await().until(() -> endpoint.data.length == 1024);
    }
}
