package com.variocube.vcmp.auth;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.variocube.vcmp.VcmpMessage;
import lombok.Value;

@Value
@JsonTypeName("Secret")
class Secret implements VcmpMessage {
    String secret;
}
