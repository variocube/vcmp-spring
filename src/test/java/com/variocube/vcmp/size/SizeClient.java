package com.variocube.vcmp.size;

import com.variocube.vcmp.VcmpListener;
import com.variocube.vcmp.VcmpSession;
import com.variocube.vcmp.VcmpTestBase;
import com.variocube.vcmp.client.BasicVcmpClient;
import com.variocube.vcmp.client.VcmpClient;

import java.io.IOException;

@VcmpClient(url = VcmpTestBase.BASE_URL + "/size")
public class SizeClient extends BasicVcmpClient {
    
    byte[] data = new byte[0];

    @VcmpListener
    public void handlePotentiallyHugeMessage(PotentiallyHugeMessage potentiallyHugeMessage, VcmpSession session) throws IOException {
        this.data = potentiallyHugeMessage.getData();
    }
}
