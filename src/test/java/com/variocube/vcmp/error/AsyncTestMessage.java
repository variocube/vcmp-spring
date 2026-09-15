package com.variocube.vcmp.error;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.variocube.vcmp.VcmpMessage;
import lombok.Value;

/** Handled by an async listener that fails deliberately — the NAK must carry that status, not 500. */
@Value
@JsonTypeName("error:AsyncTestMessage")
public class AsyncTestMessage implements VcmpMessage {
}
