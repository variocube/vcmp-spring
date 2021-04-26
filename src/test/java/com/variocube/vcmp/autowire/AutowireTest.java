package com.variocube.vcmp.autowire;

import com.variocube.vcmp.VcmpTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

public class AutowireTest extends VcmpTestBase {

    @Autowired
    private AutowireTestEndpoint endpoint;

    @Test
    public void canAutowire() {
        assertThat(endpoint.dependency).isNotNull();
    }
}
