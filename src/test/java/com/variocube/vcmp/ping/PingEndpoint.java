package com.variocube.vcmp.ping;

import com.variocube.vcmp.VcmpSession;
import com.variocube.vcmp.VcmpSessionConnected;
import com.variocube.vcmp.VcmpSessionDisconnected;
import com.variocube.vcmp.server.VcmpEndpoint;
import com.variocube.vcmp.server.VcmpSessionPool;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.IOException;

@VcmpEndpoint(path="/ping")
@RequiredArgsConstructor
public class PingEndpoint {

    @Getter
    private final VcmpSessionPool sessionPool = new VcmpSessionPool();

    @VcmpSessionConnected
    public void handleSessionConnected(VcmpSession session) throws IOException {
        sessionPool.add(session);
    }

    @VcmpSessionDisconnected
    public void handleSessionDisconnected(VcmpSession session) {
        sessionPool.remove(session);
    }
}
