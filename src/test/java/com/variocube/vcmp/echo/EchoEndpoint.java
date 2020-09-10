package com.variocube.vcmp.echo;

import com.variocube.vcmp.VcmpListener;
import com.variocube.vcmp.VcmpSession;
import com.variocube.vcmp.server.VcmpEndpoint;

import java.io.IOException;

@VcmpEndpoint(path="/echo")
public class EchoEndpoint {
    @VcmpListener
    public void handleEchoRequest(EchoRequest echoRequest, VcmpSession session) throws IOException {
        session.send(new EchoResponse(echoRequest.getMessage()));
    }
}
