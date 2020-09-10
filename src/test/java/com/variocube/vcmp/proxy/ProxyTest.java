package com.variocube.vcmp.proxy;

import com.variocube.vcmp.VcmpTestBase;
import org.junit.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class ProxyTest extends VcmpTestBase {

    @Autowired
    private ProxyTestEndpoint endpoint;

    @Test
    public void canProxy() {
        // The `ProxyTestEndpoint` is an VCMP endpoint and has an async event listener
        // Therefore Spring will create a proxy class for it.
        // The purpose of this test is to find out, whether the endpoint can be instantiated correctly.
    }
}
