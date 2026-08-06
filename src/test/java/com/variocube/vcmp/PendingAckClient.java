package com.variocube.vcmp;

import com.variocube.vcmp.client.BasicVcmpClient;
import com.variocube.vcmp.client.VcmpClient;

import java.io.IOException;

@VcmpClient(url = VcmpTestBase.BASE_URL + "/pendingack")
public class PendingAckClient extends BasicVcmpClient {

    void closeSession() throws IOException {
        getSession().close();
    }

}
