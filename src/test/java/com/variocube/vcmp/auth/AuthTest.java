package com.variocube.vcmp.auth;

import com.variocube.vcmp.VcmpTestBase;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;

import static com.variocube.vcmp.SecurityConfiguration.USERNAME;
import static org.awaitility.Awaitility.await;

public class AuthTest extends VcmpTestBase {

    @Autowired
    private AuthClient client;

    @Autowired
    private AuthEndpoint endpoint;

    @Test
    public void canAuth() throws IOException {
        await().until(client::isConnected);
        endpoint.authTestPrincipal = "";
        client.send(new Auth());
        await().until(() -> endpoint.authTestPrincipal.equals(USERNAME));
    }

    @Test
    public void canTarget() throws IOException {
        await().until(client::isConnected);
        client.secret = "";
        endpoint.sendSecret(USERNAME, "don't tell anyone");
        await().until(() -> client.secret.equals("don't tell anyone"));
    }


}
