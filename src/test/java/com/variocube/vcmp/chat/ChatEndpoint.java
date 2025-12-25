package com.variocube.vcmp.chat;

import com.variocube.vcmp.*;
import com.variocube.vcmp.server.VcmpEndpoint;
import com.variocube.vcmp.server.VcmpSessionPool;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.Collection;

@VcmpEndpoint(path="/chat")
@Slf4j
public class ChatEndpoint {

    @Getter
    private final VcmpSessionPool sessionPool = new VcmpSessionPool();

    @VcmpSessionConnected
    public void handleSessionConnected(VcmpSession session) {
        sessionPool.add(session);
    }

    @VcmpSessionDisconnected
    public void handleSessionDisconnected(VcmpSession session) {
        sessionPool.remove(session);
    }

    @VcmpListener
    public VcmpCallback<Void> forwardMessageToRecipient(ChatMessage chatMessage, VcmpSession session) {
        log.info("Forwarding chat message: {}", chatMessage);
        return sessionPool.send(chatMessage.getRecipient(), chatMessage);
    }

    public VcmpCallback<Collection<Void>> broadcast(ChatMessage chatMessage) {
        return sessionPool.broadcast(chatMessage);
    }
}
