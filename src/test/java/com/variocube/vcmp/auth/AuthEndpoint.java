package com.variocube.vcmp.auth;

import com.variocube.vcmp.VcmpListener;
import com.variocube.vcmp.VcmpTestEndpoint;
import com.variocube.vcmp.server.VcmpEndpoint;

import java.io.IOException;

@VcmpEndpoint(path="/auth")
public class AuthEndpoint extends VcmpTestEndpoint {

    String authTestPrincipal;

    @VcmpListener
    public void handleAuthTest(Auth auth, String principal) {
        this.authTestPrincipal = principal;
    }

    void sendSecret(String recipient, String secret) throws IOException {
        getSessionPool().send(recipient, new Secret(secret));
    }

}
