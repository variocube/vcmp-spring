package com.variocube.vcmp;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Value;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;

import static com.variocube.vcmp.ObjectMapperHolder.createObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VcmpHandlerTest {

    @Value
    @JsonTypeName("cube:BoxSnapshot")
    static class BoxSnapshot implements VcmpMessage {
        List<Box> boxes;
    }

    @Value
    static class Box {
        String number;
        String description;
        String lock;
        String secondaryLock;
        List<String> types;
    }

    @Test
    void objectMapperDoesntFailOnUnknownProperties() throws IOException {
        ObjectMapper objectMapper = createObjectMapper();
        objectMapper.registerSubtypes(BoxSnapshot.class);

        val json = """
          {
          "@type": "cube:BoxSnapshot",
          "boxes": [
            {
              "number": "1",
              "description": "Box 1",
              "lock": "1509000011_23",
              "secondaryLock": null,
              "types": [
                "S"
              ],
              "unknownProperty": "that's dangerous!"
            }
          ]
        }""";

        val boxSnapshot = (BoxSnapshot)objectMapper.readValue(json, VcmpMessage.class);

        assertThat(boxSnapshot.getBoxes()).hasSize(1);
    }

    @JsonTypeName("test:AckWithMarkedProblem")
    static class AckWithMarkedProblem implements VcmpMessage {
    }

    @JsonTypeName("test:NakWithMarkedProblem")
    static class NakWithMarkedProblem implements VcmpMessage {
    }

    /** Listener target whose responses carry locally marked ProblemDetails on both channels. */
    static class MarkedProblemTarget {
        @VcmpListener
        public VcmpCallback<ProblemDetail> ackWithMarkedProblem(AckWithMarkedProblem message) {
            return VcmpCallback.completed(markedProblemDetail());
        }

        @VcmpListener
        public VcmpCallback<Void> nakWithMarkedProblem(NakWithMarkedProblem message) {
            return VcmpCallback.failed(markedProblemDetail());
        }

        private static ProblemDetail markedProblemDetail() {
            val problemDetail = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
            problemDetail.setTitle("Session closed");
            return LocalConnectionProblem.mark(problemDetail);
        }
    }

    /**
     * Pins the outbound wire strip at the raw-frame level. Unlike the end-to-end tests in ErrorTest, this cannot
     * be masked by the receiving client's own parse-side scrub: it asserts the serialized frame text a
     * non-scrubbing peer (vcmp-js, pre-6.1 vcmp-spring) would observe.
     */
    @Test
    void ackAndNakFramesNeverCarryTheLocalConnectionMarker() throws IOException {
        val handler = new VcmpHandler(new MarkedProblemTarget());
        val webSocketSession = mock(WebSocketSession.class);
        when(webSocketSession.getId()).thenReturn("raw-frame-test");
        when(webSocketSession.isOpen()).thenReturn(true);
        when(webSocketSession.getTextMessageSizeLimit()).thenReturn(8192);

        handler.afterConnectionEstablished(webSocketSession);
        handler.handleMessage(webSocketSession,
                new TextMessage(VcmpFrame.createMessage("{\"@type\":\"test:AckWithMarkedProblem\"}").serialize()));
        handler.handleMessage(webSocketSession,
                new TextMessage(VcmpFrame.createMessage("{\"@type\":\"test:NakWithMarkedProblem\"}").serialize()));

        // listener dispatch and the ACK/NAK sends run on the VCMP executor
        val captor = ArgumentCaptor.forClass(TextMessage.class);
        await().untilAsserted(() -> {
            verify(webSocketSession, atLeast(2)).sendMessage(captor.capture());
            val frames = captor.getAllValues()
                    .stream()
                    .map(TextMessage::getPayload)
                    .toList();
            assertThat(frames).anyMatch(frame -> frame.startsWith("ACK"));
            assertThat(frames).anyMatch(frame -> frame.startsWith("NAK"));
        });
        assertThat(captor.getAllValues())
                .extracting(TextMessage::getPayload)
                .noneMatch(frame -> frame.contains(LocalConnectionProblem.PROPERTY));
    }

    @Test
    void parsedProblemDetailCannotCarryAForgedLocalConnectionMarker() {
        val handler = new VcmpHandler(new Object());

        // a raw NAK payload from a (misbehaving or pre-6.1) peer claiming to be a local problem
        val payload = """
          {
            "status": 503,
            "title": "Session closed",
            "properties": {
              "%s": true,
              "other": "survives"
            }
          }""".formatted(LocalConnectionProblem.PROPERTY);

        val problemDetail = handler.parseProblemDetail(payload);

        assertThat(LocalConnectionProblem.isMarked(problemDetail)).isFalse();
        assertThat(problemDetail.getStatus()).isEqualTo(503);
        assertThat(problemDetail.getProperties()).containsEntry("other", "survives");
    }
}
