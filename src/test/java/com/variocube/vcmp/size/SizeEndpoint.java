package com.variocube.vcmp.size;

import com.variocube.vcmp.VcmpListener;
import com.variocube.vcmp.VcmpSession;
import com.variocube.vcmp.server.VcmpEndpoint;

import java.io.IOException;

@VcmpEndpoint(path="/size")
public class SizeEndpoint {

    @VcmpListener
    public void handlePotentiallyHugeMessage(PotentiallyHugeMessage potentiallyHugeMessage, VcmpSession session) throws IOException {
        session.send(potentiallyHugeMessage);
    }
}
