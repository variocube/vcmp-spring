package com.variocube.vcmp.echo2;

import com.variocube.vcmp.VcmpListener;
import com.variocube.vcmp.server.VcmpEndpoint;

@VcmpEndpoint(path="/echo2")
public class Echo2Endpoint {
    @VcmpListener
    public EchoResponse handleEchoRequest(EchoRequest echoRequest) {
        return new EchoResponse(echoRequest.getMessage());
    }
}
