package com.variocube.vcmp.error;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.variocube.vcmp.VcmpMessage;
import lombok.Value;

/** Handled by a sync listener that joins a failed future, so the CompletionException is thrown synchronously. */
@Value
@JsonTypeName("error:JoinedFutureTestMessage")
public class JoinedFutureTestMessage implements VcmpMessage {
}
