package com.variocube.vcmp.chat;

import com.variocube.vcmp.SecurityConfiguration;
import com.variocube.vcmp.VcmpTestBase;
import com.variocube.vcmp.client.VcmpConnectionManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

public class ChatTest extends VcmpTestBase {

    @Test
    public void canChat() throws IOException {

        ChatClient alice = new ChatClient(SecurityConfiguration.ALICE_USERNAME, SecurityConfiguration.ALICE_PASSWORD, Collections.singletonList("flowers"));
        ChatClient bob = new ChatClient(SecurityConfiguration.BOB_USERNAME, SecurityConfiguration.BOB_PASSWORD, Collections.emptyList());

        try (VcmpConnectionManager aliceConnection = new VcmpConnectionManager(alice, VcmpTestBase.BASE_URL + "/chat");
             VcmpConnectionManager bobConnection = new VcmpConnectionManager(bob, VcmpTestBase.BASE_URL + "/chat")) {

            aliceConnection.start();
            bobConnection.start();

            await().until(alice::isConnected);
            await().until(bob::isConnected);

            //
            // Assert that the callback only returns when alice received the message
            //
            AtomicBoolean ack = new AtomicBoolean(false);
            bob.send(new ChatMessage("alice", "Hi Alice!"), () -> ack.set(true));
            await().untilTrue(ack);

            assertThat(alice.getReceived()).hasSize(1);
            assertThat(alice.getReceived().get(0).getMessage()).isEqualTo("Hi Alice!");

            //
            // Send a message that alice will refuse, because she hates flowers.
            // Assert for nak.
            //
            AtomicBoolean nak = new AtomicBoolean(false);
            bob.send(new ChatMessage("alice", "I will bring you flowers!"), null, () -> nak.set(true));
            await().untilTrue(nak);

        }
    }


}
