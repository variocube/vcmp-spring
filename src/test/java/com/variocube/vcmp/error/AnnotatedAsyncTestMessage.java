package com.variocube.vcmp.error;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.variocube.vcmp.VcmpMessage;
import lombok.Value;

/** Handled by an async listener failing with a {@code @ResponseStatus}-annotated exception: both wrappers at once. */
@Value
@JsonTypeName("error:AnnotatedAsyncTestMessage")
public class AnnotatedAsyncTestMessage implements VcmpMessage {
}
