package com.variocube.vcmp;

import lombok.val;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VcmpFrameTest {

    @Test
    void canParseMessage() {
        val frame = VcmpFrame.parse("MSG123456789012payload");
        assertThat(frame.getType()).isEqualTo(VcmpFrame.Type.MSG);
        assertThat(frame.getId()).isEqualTo("123456789012");
        assertThat(frame.getPayload()).isEqualTo("payload");
    }

    @Test
    void canParseAck_withoutPayload() {
        val frame = VcmpFrame.parse("ACK123456789012");
        assertThat(frame.getType()).isEqualTo(VcmpFrame.Type.ACK);
        assertThat(frame.getId()).isEqualTo("123456789012");
        assertThat(frame.getPayload()).isNull();
    }

    @Test
    void canParseAck_withPayload() {
        val frame = VcmpFrame.parse("ACK123456789012payload");
        assertThat(frame.getType()).isEqualTo(VcmpFrame.Type.ACK);
        assertThat(frame.getId()).isEqualTo("123456789012");
        assertThat(frame.getPayload()).isEqualTo("payload");
    }

    @Test
    void canParseNak_withoutPayload() {
        val frame = VcmpFrame.parse("NAK123456789012");
        assertThat(frame.getType()).isEqualTo(VcmpFrame.Type.NAK);
        assertThat(frame.getId()).isEqualTo("123456789012");
        assertThat(frame.getPayload()).isNull();
    }

    @Test
    void canParseNak_withPayload() {
        val frame = VcmpFrame.parse("NAK123456789012payload");
        assertThat(frame.getType()).isEqualTo(VcmpFrame.Type.NAK);
        assertThat(frame.getId()).isEqualTo("123456789012");
        assertThat(frame.getPayload()).isEqualTo("payload");
    }

    @Test
    void canParseHeartbeat() {
        val frame = VcmpFrame.parse("HBT420");
        assertThat(frame.getType()).isEqualTo(VcmpFrame.Type.HBT);
        assertThat(frame.getId()).isNull();
        assertThat(frame.getPayload()).isNull();
        assertThat(frame.getHeartbeatInterval()).isEqualTo(420);
    }

    @Test
    void canSerializeHeartbeat() {
        val frame = VcmpFrame.createHeartbeat(420).serialize();
        assertThat(frame).isEqualTo("HBT420");
    }

    @Test
    void canSerializeMessage() {
        val frame = VcmpFrame.createMessage("payload").serialize();
        assertThat(frame).startsWith("MSG")
            .endsWith("payload");
    }

    @Test
    void canSerializeAck_withPayload() {
        val frame = VcmpFrame.createAck("123456789012", "payload").serialize();
        assertThat(frame).isEqualTo("ACK123456789012payload");
    }

    @Test
    void canSerializeAck_withoutPayload() {
        val frame = VcmpFrame.createAck("123456789012", null).serialize();
        assertThat(frame).isEqualTo("ACK123456789012");
    }

    @Test
    void canSerializeNak_withPayload() {
        val frame = VcmpFrame.createNak("123456789012", "payload").serialize();
        assertThat(frame).isEqualTo("NAK123456789012payload");
    }

    @Test
    void canSerializeNak_withoutPayload() {
        val frame = VcmpFrame.createNak("123456789012", null).serialize();
        assertThat(frame).isEqualTo("NAK123456789012");
    }
}