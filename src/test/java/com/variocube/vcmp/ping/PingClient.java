package com.variocube.vcmp.ping;

import com.variocube.vcmp.VcmpTestBase;
import com.variocube.vcmp.client.BasicVcmpClient;
import com.variocube.vcmp.client.VcmpClient;

import java.io.IOException;

@VcmpClient(url = VcmpTestBase.BASE_URL + "/ping")
public class PingClient extends BasicVcmpClient {

    int getHeartbeatReceivedCount() {
        return getSession().getHeartbeatReceivedCount();
    }

    public void initiateHeartbeat(int interval) throws IOException {
        getSession().initiateHeartbeat(interval);
    }
}
