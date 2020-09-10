package com.variocube.vcmp.chat;

import com.variocube.vcmp.VcmpListener;
import com.variocube.vcmp.client.BasicVcmpClient;
import com.variocube.vcmp.client.VcmpHttpHeaders;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Slf4j
public class ChatClient extends BasicVcmpClient {

    private final String username;
    private final String password;
    private final List<String> banWords;

    @Getter
    private final ArrayList<ChatMessage> received = new ArrayList<>();

    @VcmpHttpHeaders
    public void handleHttpHeaders(HttpHeaders headers) {
        headers.setBasicAuth(this.username, this.password);
    }

    @VcmpListener
    public void handleChatMessage(ChatMessage message) {
        for (String banWord : banWords) {
            if (message.getMessage().contains(banWord)) {
                throw new RuntimeException("Encountered ban word.");
            }
        }

        log.info("Received message: {}", message);

        received.add(message);
    }

}
