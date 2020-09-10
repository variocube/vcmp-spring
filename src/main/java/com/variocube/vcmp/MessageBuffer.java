package com.variocube.vcmp;

final class MessageBuffer {
    private final StringBuilder buffer = new StringBuilder();

    void append(String payload) {
        buffer.append(payload);
    }

    String flush(String lastPayload) {
        buffer.append(lastPayload);
        String result = buffer.toString();
        buffer.setLength(0);
        return result;
    }
}
