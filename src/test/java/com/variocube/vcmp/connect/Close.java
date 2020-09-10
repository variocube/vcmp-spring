package com.variocube.vcmp.connect;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.variocube.vcmp.VcmpMessage;
import lombok.Value;

@Value
@JsonTypeName("Close")
public class Close implements VcmpMessage {
}
