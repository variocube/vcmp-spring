package com.variocube.vcmp.echo;

import com.variocube.vcmp.VcmpListener;
import com.variocube.vcmp.VcmpTestBase;
import com.variocube.vcmp.client.BasicVcmpClient;
import com.variocube.vcmp.client.VcmpClient;

@VcmpClient(url = VcmpTestBase.BASE_URL + "/echo")
public class EchoClient extends BasicVcmpClient {

    String echoResponse;

    @VcmpListener
    public void handleEchoResponse(EchoResponse echoResponse) {
        this.echoResponse = echoResponse.getMessage();
    }

}
