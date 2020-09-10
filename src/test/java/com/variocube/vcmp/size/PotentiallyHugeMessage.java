package com.variocube.vcmp.size;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.variocube.vcmp.VcmpMessage;
import lombok.Value;

@Value
@JsonTypeName("PotentiallyHugeMessage")
class PotentiallyHugeMessage implements VcmpMessage {
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    byte[] data;
}
