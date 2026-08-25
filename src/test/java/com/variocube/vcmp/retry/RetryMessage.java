package com.variocube.vcmp.retry;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.variocube.vcmp.VcmpMessage;
import lombok.Value;

@Value
@JsonTypeName("retry:RetryMessage")
public class RetryMessage implements VcmpMessage {
}
