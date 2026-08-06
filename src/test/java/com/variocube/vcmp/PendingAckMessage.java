package com.variocube.vcmp;

import com.fasterxml.jackson.annotation.JsonTypeName;
import lombok.Value;

@Value
@JsonTypeName("pendingack:PendingAckMessage")
public class PendingAckMessage implements VcmpMessage {
}
