package com.variocube.vcmp.connect;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.variocube.vcmp.VcmpMessage;
import lombok.Value;

@Value
@JsonTypeName("ChangeFooMessage")
class ChangeFooMessage implements VcmpMessage {
    String newFoo;
}
