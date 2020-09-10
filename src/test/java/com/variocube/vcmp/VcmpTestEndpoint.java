package com.variocube.vcmp;

import com.variocube.vcmp.server.VcmpSessionPool;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class VcmpTestEndpoint {

    @Getter
    private final VcmpSessionPool sessionPool = new VcmpSessionPool();

    @VcmpSessionDisconnected
    public void handleSessionDisconnected(VcmpSession session) {
        log.info("Session disconnected.");
        sessionPool.remove(session);
    }

    @VcmpSessionConnected
    public void handleSessionConnected(VcmpSession session) {
        log.info("Session connected.");
        sessionPool.add(session);
    }

}
