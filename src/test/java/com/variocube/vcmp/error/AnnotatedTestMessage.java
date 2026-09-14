package com.variocube.vcmp.error;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.variocube.vcmp.VcmpMessage;
import lombok.Value;

/** Handled by a sync listener throwing a {@code @ResponseStatus}-annotated exception. */
@Value
@JsonTypeName("error:AnnotatedTestMessage")
public class AnnotatedTestMessage implements VcmpMessage {
}
