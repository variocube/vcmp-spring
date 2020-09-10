package com.variocube.vcmp.auth;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.variocube.vcmp.VcmpMessage;
import lombok.Value;

@Value
@JsonTypeName("AuthTest")
class Auth implements VcmpMessage {
}
