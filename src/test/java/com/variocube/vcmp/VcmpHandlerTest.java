package com.variocube.vcmp;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Value;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

public class VcmpHandlerTest {

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
    public void objectMapperDoesntFailOnUnknownProperties() throws IOException {
        ObjectMapper objectMapper = VcmpHandler.createObjectMapper();
        objectMapper.registerSubtypes(BoxSnapshot.class);

        BoxSnapshot boxSnapshot = (BoxSnapshot)objectMapper.readValue("{\n" +
                "  \"@type\": \"cube:BoxSnapshot\",\n" +
                "  \"boxes\": [\n" +
                "    {\n" +
                "      \"number\": \"1\",\n" +
                "      \"description\": \"Box 1\",\n" +
                "      \"lock\": \"1509000011_23\",\n" +
                "      \"secondaryLock\": null,\n" +
                "      \"types\": [\n" +
                "        \"S\"\n" +
                "      ],\n" +
                "      \"unknownProperty\": \"that's dangerous!\"\n" +
                "    }\n" +
                "  ]\n" +
                "}\n", VcmpMessage.class);
    }
}
