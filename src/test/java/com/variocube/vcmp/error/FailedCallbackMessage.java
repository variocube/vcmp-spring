package com.variocube.vcmp.error;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.variocube.vcmp.VcmpMessage;
import lombok.Value;

@Value
@JsonTypeName("error:FailedCallbackMessage")
public class FailedCallbackMessage implements VcmpMessage {
}
