package com.variocube.vcmp.server;

import com.variocube.vcmp.VcmpCallback;
import com.variocube.vcmp.VcmpMessage;
import com.variocube.vcmp.VcmpSession;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class VcmpSessionPool {

    private final Set<VcmpSession> sessions = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public void add(VcmpSession session) {
        sessions.add(session);
    }

    public void remove(VcmpSession session) {
        sessions.remove(session);
    }

    public void broadcast(VcmpMessage message) {
        for (VcmpSession session : getOpenSessions()) {
            try {
                session.send(message);
            } catch (IOException e) {
                // ignore errors in broadcast
            }
        }
    }

    public VcmpCallback send(String recipient, VcmpMessage message) throws IOException {
        for (VcmpSession session : getOpenSessions()) {
            if (session.hasUsername(recipient)) {
                try {
                    return session.send(message);
                } catch (IOException e) {
                    // try to send on another session
                }
            }
        }
        throw new IOException("Could not send message to recipient.");
    }

    public int getSessionCount() {
        return sessions.size();
    }

    public boolean hasSession(String username) {
        return sessions.stream().anyMatch(session -> session.hasUsername(username));
    }

    private List<VcmpSession> getOpenSessions() {
        ArrayList<VcmpSession> openSessions = new ArrayList<>(sessions.size());
        for (VcmpSession session : new ArrayList<>(sessions)) {
            if (!session.isOpen()) {
                log.warn("Session was closed without removal from pool. Removing now: {}", session.getId());
                sessions.remove(session);
            }
            else {
                openSessions.add(session);
            }
        }
        return openSessions;
    }

}
