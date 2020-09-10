package com.variocube.vcmp.connect;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.variocube.vcmp.VcmpMessage;
import lombok.Value;

@Value
@JsonTypeName("Base64Message")
public class Base64Message implements VcmpMessage {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    byte[] data;
}
