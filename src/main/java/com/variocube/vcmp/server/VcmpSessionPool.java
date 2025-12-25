package com.variocube.vcmp.server;

import com.variocube.vcmp.VcmpCallback;
import com.variocube.vcmp.VcmpMessage;
import com.variocube.vcmp.VcmpSession;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages a pool of VcmpSessions, providing methods for broadcasting messages and sending messages to specific recipients.
 */
@Slf4j
public class VcmpSessionPool {

    private final Set<VcmpSession> sessions = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * Adds a session to the pool.
     * @param session The session to add.
     */
    public void add(VcmpSession session) {
        sessions.add(session);
    }

    /**
     * Removes a session from the pool.
     * @param session The session to remove.
     */
    public void remove(VcmpSession session) {
        sessions.remove(session);
    }

    /**
     * Sends the specified message to all open sessions.
     * @param message The message to send.
     * @return The callback for the broadcast operation.
     */
    public VcmpCallback<Collection<Void>> broadcast(VcmpMessage message) {
        return broadcast(message, Void.class);
    }

    /**
     * Sends the specified message to all open sessions.
     * @param message The message to send.
     * @param resultClass The class of the expected result.
     * @return The callback for the broadcast operation.
     * @param <T> The type of the expected result.
     */
    public <T> VcmpCallback<Collection<T>> broadcast(VcmpMessage message, Class<T> resultClass) {
        val callbacks = new ArrayList<VcmpCallback<T>>();
        for (VcmpSession session : getOpenSessions()) {
            callbacks.add(session.send(message, resultClass));
        }
        return VcmpCallback.all(callbacks);
    }

    /**
     * Sends the specified message to the specified recipient.
     * @param recipient The username of the recipient.
     * @param message The message to send.
     * @return The callback for the send operation.
     */
    public VcmpCallback<Void> send(String recipient, VcmpMessage message) {
        return send(recipient, message, Void.class);
    }

    /**
     * Sends the specified message to the specified recipient.
     * @param recipient The username of the recipient.
     * @param message The message to send.
     * @param resultClass The class of the expected result.
     * @return The callback for the send operation.
     * @param <T> The type of the expected result.
     */
    public <T> VcmpCallback<T> send(String recipient, VcmpMessage message, Class<T> resultClass) {
        val callbacks = getOpenSessions().stream()
                .filter(session -> session.hasUsername(recipient))
                .map(session -> session.send(message, resultClass))
                .toList();
        return VcmpCallback.any(callbacks);
    }

    /**
     * Gets the number of sessions in the pool.
     * @return The number of sessions.
     */
    public int getSessionCount() {
        return sessions.size();
    }

    /**
     * Checks if the pool contains a session with the specified username.
     * @param username The username to check.
     * @return True if a session with the username exists, false otherwise.
     */
    public boolean hasSession(String username) {
        return sessions.stream()
                .filter(VcmpSession::isOpen)
                .anyMatch(session -> session.hasUsername(username));
    }

    /**
     * Gets a list of open sessions in the pool.
     * @return A list of open sessions.
     */
    public List<VcmpSession> getOpenSessions() {
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
