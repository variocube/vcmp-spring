package com.variocube.vcmp.echo2;

import com.variocube.vcmp.VcmpTestBase;
import com.variocube.vcmp.client.BasicVcmpClient;
import com.variocube.vcmp.client.VcmpClient;

@VcmpClient(url = VcmpTestBase.BASE_URL + "/echo2")
public class Echo2Client extends BasicVcmpClient {
}
