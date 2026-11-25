package com.variocube.vcmp.aut;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.variocube.vcmp.VcmpMessage;
import lombok.Value;

@Value
@JsonTypeName("aut:Hello")
public class Hello implements VcmpMessage {
    String text;
}
