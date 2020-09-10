package com.variocube.vcmp.autowire;

import com.variocube.vcmp.server.VcmpEndpoint;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;

@VcmpEndpoint(path = "/autowire")
@RequiredArgsConstructor
public class AutowireTestEndpoint {

    @Autowired
    final AutowireTestDependency dependency;

}
