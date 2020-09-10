package com.variocube.vcmp.echo;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.variocube.vcmp.VcmpMessage;
import lombok.Value;

@Value
@JsonTypeName("EchoRequest")
class EchoRequest implements VcmpMessage {
    String message;
}
