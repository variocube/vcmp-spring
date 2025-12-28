package com.variocube.vcmp.echo2;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.variocube.vcmp.VcmpMessage;
import lombok.Value;

@Value
@JsonTypeName("echo2:EchoRequest")
public class EchoRequest implements VcmpMessage {
    String message;
}
