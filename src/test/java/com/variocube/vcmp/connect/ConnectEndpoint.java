package com.variocube.vcmp.connect;

import com.variocube.vcmp.VcmpListener;
import com.variocube.vcmp.VcmpSession;
import com.variocube.vcmp.VcmpTestEndpoint;
import com.variocube.vcmp.server.VcmpEndpoint;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;

@VcmpEndpoint(path = "/connect")
@Slf4j
public class ConnectEndpoint extends VcmpTestEndpoint {

    String foo;

    byte[] data = new byte[0];

    @VcmpListener
    public void handleChangeFooMessage(ChangeFooMessage changeFooMessage) {
        foo = changeFooMessage.getNewFoo();
    }

    @VcmpListener
    public void handleClose(Close close, VcmpSession session) throws IOException {
        log.info("Retrieved close message. Closing session {}", session.getId());
        session.close();
    }

    @VcmpListener
    public void handleBase64(Base64Message base64Message) {
        this.data = base64Message.getData();
    }
}
