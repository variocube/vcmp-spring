package com.variocube.vcmp.chat;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.variocube.vcmp.VcmpMessage;
import lombok.Value;

@Value
@JsonTypeName("CascadeMessage")
class ChatMessage implements VcmpMessage {
    String recipient;
    String message;
}
