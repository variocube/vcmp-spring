package com.variocube.vcmp.server;

import com.variocube.vcmp.AnnotatedBeanCondition;

class VcmpEndpointCondition extends AnnotatedBeanCondition {
    VcmpEndpointCondition() {
        super(VcmpEndpoint.class);
    }
}
