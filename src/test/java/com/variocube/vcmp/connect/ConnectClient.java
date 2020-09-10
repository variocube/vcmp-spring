package com.variocube.vcmp.connect;

import com.variocube.vcmp.VcmpSession;
import com.variocube.vcmp.VcmpTestBase;
import com.variocube.vcmp.client.BasicVcmpClient;
import com.variocube.vcmp.client.VcmpClient;

@VcmpClient(url = ConnectClient.URL)
public class ConnectClient extends BasicVcmpClient {
    static final String URL = VcmpTestBase.BASE_URL + "/connect";

    boolean handleSessionConnectedCalled = false;

    @Override
    public void handleSessionConnected(VcmpSession session) {
        super.handleSessionConnected(session);
        handleSessionConnectedCalled = true;
    }
}
