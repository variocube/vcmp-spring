package com.variocube.vcmp.pendingack;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.variocube.vcmp.VcmpMessage;
import lombok.Value;

@Value
@JsonTypeName("pendingack:PendingAckMessage")
public class PendingAckMessage implements VcmpMessage {
}
