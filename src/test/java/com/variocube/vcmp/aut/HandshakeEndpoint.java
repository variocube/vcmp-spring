package com.variocube.vcmp.aut;

import com.variocube.vcmp.VcmpAuthentication;
import com.variocube.vcmp.VcmpAuthenticator;
import com.variocube.vcmp.VcmpHandshake;
import com.variocube.vcmp.VcmpHandshakeAuthenticator;
import com.variocube.vcmp.VcmpListener;
import com.variocube.vcmp.VcmpSession;
import com.variocube.vcmp.VcmpSessionConnected;
import com.variocube.vcmp.server.VcmpEndpoint;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An endpoint in transition mode: old clients put their token into the path ({@code -} for none), new ones send AUT.
 * Path token {@code bad...} is invalid.
 */
@VcmpEndpoint(path = HandshakeEndpoint.PATH, authenticated = true, authTimeoutMillis = 700)
public class HandshakeEndpoint {

    public static final String PATH = "/aut-handshake/{token}";

    public final List<String> connected = new CopyOnWriteArrayList<>();

    @VcmpHandshakeAuthenticator
    public VcmpAuthentication authenticateHandshake(VcmpHandshake handshake) {
        String token = handshake.pathVariables().get("token");
        if (token.equals("-")) {
            return null;
        }
        if (token.startsWith("bad")) {
            throw new IllegalArgumentException("Invalid token");
        }
        if (token.equals("error")) {
            throw new AssertionError("Authenticator bug");
        }
        return VcmpAuthentication.of(new UserPrincipal(token));
    }

    @VcmpAuthenticator
    public VcmpAuthentication authenticate(TokenCredential credential) {
        if (credential.token().startsWith("bad")) {
            return null;
        }
        return VcmpAuthentication.of(new UserPrincipal(credential.token()));
    }

    @VcmpSessionConnected
    public void handleConnected(VcmpSession session) {
        connected.add(session.getPrincipal().getName());
    }

    @VcmpListener
    public String handleHello(Hello hello, VcmpSession session) {
        return "hello " + session.getPrincipal().getName();
    }
}
