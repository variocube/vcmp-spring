package com.variocube.vcmp;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Value;
import lombok.val;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static com.variocube.vcmp.ObjectMapperHolder.createObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

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
