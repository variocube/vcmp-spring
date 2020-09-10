package com.variocube.vcmp;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.util.Base64Utils;

import java.security.SecureRandom;

@Value
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
class VcmpFrame {

    enum Type {
        ACK,
        NAK,
        MSG,
        HBT
    }

    Type type;
    String id;
    String payload;
    Integer heartbeatInterval;

    private static final int TYPE_INDEX = 0;
    private static final int TYPE_LENGTH = 3;

    private static final int ID_INDEX = TYPE_INDEX + TYPE_LENGTH;
    private static final int ID_LENGTH = 12;

    private static final int PAYLOAD_INDEX = ID_INDEX + ID_LENGTH;

    private static final int HEARTBEAT_INDEX = TYPE_INDEX + TYPE_LENGTH;

    static VcmpFrame parse(String raw) {
        Type type = Type.valueOf(raw.substring(TYPE_INDEX, ID_INDEX));
        if (type == Type.HBT) {
            int heartbeatInterval = Integer.parseInt(raw.substring(HEARTBEAT_INDEX));
            return new VcmpFrame(type, null, null, heartbeatInterval);
        }
        else {
            String id = raw.substring(ID_INDEX, PAYLOAD_INDEX);
            String payload = (raw.length() > PAYLOAD_INDEX) ? raw.substring(PAYLOAD_INDEX) : null;
            return new VcmpFrame(type, id, payload, null);
        }
    }

    String serialize() {
        if (type == Type.HBT) {
            return type.name() + heartbeatInterval.toString();
        }
        else {
            return type.name() + (id != null ? id : "") + (payload != null ? payload : "");
        }
    }

    static VcmpFrame createAck(String id) {
        return new VcmpFrame(Type.ACK, id, null, null);
    }

    static VcmpFrame createNak(String id) {
        return new VcmpFrame(Type.NAK, id, null, null);
    }

    static VcmpFrame createMessage(String payload) {
        return new VcmpFrame(Type.MSG, generateId(), payload, null);
    }

    static VcmpFrame createHeartbeat(int heartbeatInterval) {
        return new VcmpFrame(Type.HBT, null, null, heartbeatInterval);
    }

    private static final int ID_BYTES = 9;
    private static final SecureRandom random = new SecureRandom();

    private static String generateId() {
        byte[] randomBytes = new byte[ID_BYTES];
        random.nextBytes(randomBytes);
        String id = Base64Utils.encodeToUrlSafeString(randomBytes);
        assert id.length() == ID_LENGTH;
        return id;
    }
}
