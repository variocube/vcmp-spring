package com.variocube.vcmp;

import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Slf4j
@TestMethodOrder(value = MethodOrderer.MethodName.class)
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

        BoxSnapshot boxSnapshot = (BoxSnapshot) objectMapper.readValue("{\n" +
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
        assertEquals(1, boxSnapshot.boxes.size());
    }

    private void wait(int intervall, TimeUnit unit ) {
        await()
                .pollDelay(intervall, unit)
                .untilAsserted(() -> assertTrue(true));
    }

    @Test
    public void testHandleMessage_1_SingleMessage() throws JsonProcessingException {
        WebSocketSession session = buildWebSession();
        //Arrange
        var executor = Executor.getExecutor();
        var startCnt = executor.getCachedPoolActiveCount();
        var handlerDummy = new BoxSnapshotHandler();
        VcmpHandler handler = new VcmpHandler(handlerDummy);
        ObjectMapper objectMapper = handler.getObjectMapper();
        objectMapper.registerSubtypes(BoxSnapshot.class);

        var msg1 = VcmpFrame.createMessage(buildJsonMsg()).serialize();

        BoxSnapshot boxSnapshot = (BoxSnapshot) objectMapper.readValue(buildJsonMsg(), VcmpMessage.class);
        assertEquals(1, boxSnapshot.boxes.size());

        TextMessage message = new TextMessage(msg1);
        handler.afterConnectionEstablished(session);
        log.info("after afterConnectionEstablished");
        wait(1, TimeUnit.SECONDS);
        assertEquals(1, handler.getSessions().size());

        handler.handleMessage(session, message);
        log.info("after handleMessage");
        wait(1, TimeUnit.SECONDS);
        handler.afterConnectionClosed(session, CloseStatus.NORMAL);
        log.info("after afterConnectionClosed");
        wait(1, TimeUnit.SECONDS);
        //Inspect executor
        assertEquals(0, handler.getSessions().size());
        assertTrue(startCnt<=executor.getCachedPoolActiveCount());
        log.info("await 5sec before");
        wait(3, TimeUnit.SECONDS);
        assertEquals(startCnt, executor.getCachedPoolActiveCount());
        log.info("await 5sec after");
    }

    @Test
    public void testHandleMessage_2_MultipleMessages() throws JsonProcessingException {
        WebSocketSession session = buildWebSession();
        //Arrange
        var executor = Executor.getExecutor();
        var startCnt = executor.getCachedPoolActiveCount();
        var handlerDummy = new BoxSnapshotHandler();
        VcmpHandler handler = new VcmpHandler(handlerDummy);
        ObjectMapper objectMapper = handler.getObjectMapper();
        objectMapper.registerSubtypes(BoxSnapshot.class);

        var msg1 = VcmpFrame.createMessage(buildJsonMsg()).serialize();

        BoxSnapshot boxSnapshot = (BoxSnapshot) objectMapper.readValue(buildJsonMsg(), VcmpMessage.class);
        assertEquals(1, boxSnapshot.boxes.size());

        TextMessage message = new TextMessage(msg1);
        handler.afterConnectionEstablished(session);

        //Message 2
        for (int i = 0; i < 10; i++) {
            handler.handleMessage(session, new TextMessage(VcmpFrame.createMessage(buildJsonMsg()).serialize()));
            //wait(1, TimeUnit.MILLISECONDS);
        }

        //Inspect executor
        wait(500, TimeUnit.MILLISECONDS);
        assertEquals(startCnt, executor.getCachedPoolActiveCount());

    }

    private String buildJsonMsg() {
        // return "{\"@type\": \"app:LockStatusChanged\"}";
        return "{\n" +
                "  \"@type\": \"cube:BoxSnapshot\",\n" +
                "  \"boxes\": [\n" +
                "    {\n" +
                "      \"number\": \"1\",\n" +
                "      \"description\": \"Box 1\",\n" +
                "      \"lock\": \"1509000011_23\",\n" +
                "      \"secondaryLock\": null,\n" +
                "      \"types\": [\n" +
                "        \"S\"\n" +
                "      ]\n" +
                "    }\n" +
                "  ]\n" +
                "}\n";
    }

    private WebSocketSession buildWebSession() {
        return new WebSocketSession() {
            @Override
            public String getId() {
                return "S123";
            }

            @Override
            public URI getUri() {
                return null;
            }

            @Override
            public HttpHeaders getHandshakeHeaders() {
                return null;
            }

            @Override
            public Map<String, Object> getAttributes() {
                return null;
            }

            @Override
            public Principal getPrincipal() {
                return null;
            }

            @Override
            public InetSocketAddress getLocalAddress() {
                return null;
            }

            @Override
            public InetSocketAddress getRemoteAddress() {
                return null;
            }

            @Override
            public String getAcceptedProtocol() {
                return null;
            }

            @Override
            public void setTextMessageSizeLimit(int messageSizeLimit) {
            }

            @Override
            public int getTextMessageSizeLimit() {
                return 256;
            }

            @Override
            public void setBinaryMessageSizeLimit(int messageSizeLimit) {
            }

            @Override
            public int getBinaryMessageSizeLimit() {
                return 256;
            }

            @Override
            public List<WebSocketExtension> getExtensions() {
                return null;
            }

            @Override
            public void sendMessage(WebSocketMessage<?> message) throws IOException {
            }

            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public void close() throws IOException {
            }

            @Override
            public void close(CloseStatus status) throws IOException {
            }
        };
    }
}
