package com.variocube.vcmp.error;

import com.variocube.vcmp.VcmpTestBase;
import com.variocube.vcmp.client.BasicVcmpClient;
import com.variocube.vcmp.client.VcmpClient;

@VcmpClient(url = VcmpTestBase.BASE_URL + "/errorVcmp")
public class ErrorClient extends BasicVcmpClient {
}
