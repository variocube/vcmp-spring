package com.variocube.vcmp.error;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.variocube.vcmp.VcmpMessage;
import lombok.Value;

/** Handled by a listener that fails by accident — an unclassified failure, the peer's 500. */
@Value
@JsonTypeName("error:CrashTestMessage")
public class CrashTestMessage implements VcmpMessage {
}
