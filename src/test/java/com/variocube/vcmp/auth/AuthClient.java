package com.variocube.vcmp.auth;

import com.variocube.vcmp.UserServiceImpl;
import com.variocube.vcmp.VcmpListener;
import com.variocube.vcmp.VcmpTestBase;
import com.variocube.vcmp.client.BasicVcmpClient;
import com.variocube.vcmp.client.VcmpClient;
import com.variocube.vcmp.client.VcmpHttpHeaders;
import org.springframework.http.HttpHeaders;

@VcmpClient(url = VcmpTestBase.BASE_URL + "/auth")
public class AuthClient extends BasicVcmpClient {

    @VcmpHttpHeaders
    public void handleHttpHeaders(HttpHeaders httpHeaders) {
        httpHeaders.setBasicAuth(UserServiceImpl.USERNAME, UserServiceImpl.PASSWORD);
    }

    String secret;

    @VcmpListener
    public void handleSecret(Secret secret) {
        this.secret = secret.getSecret();
    }

}
