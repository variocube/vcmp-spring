package com.variocube.vcmp.aut;

import com.variocube.vcmp.VcmpAuthentication;
import com.variocube.vcmp.VcmpAuthenticator;
import com.variocube.vcmp.VcmpClientAuthentication;
import com.variocube.vcmp.VcmpHandler;
import com.variocube.vcmp.VcmpHandshake;
import com.variocube.vcmp.VcmpHandshakeAuthenticator;
import com.variocube.vcmp.server.VcmpEndpoint;
import com.variocube.vcmp.server.VcmpServerAutoConfiguration;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The opt-in must be explicit and complete: no endpoint silently accepts sessions it was meant to authenticate. */
class AutConfigurationTest {

    @VcmpEndpoint(path = "/without-opt-in")
    public static class AuthenticatorWithoutOptIn {
        @VcmpAuthenticator
        public VcmpAuthentication authenticate(TokenCredential credential) {
            return null;
        }
    }

    @VcmpEndpoint(path = "/handshake-without-opt-in")
    public static class HandshakeAuthenticatorWithoutOptIn {
        @VcmpHandshakeAuthenticator
        public VcmpAuthentication authenticate(VcmpHandshake handshake) {
            return null;
        }
    }

    @VcmpEndpoint(path = "/opt-in-without-authenticator", authenticated = true)
    public static class OptInWithoutAuthenticator {
    }

    public static class WrongReturnType {
        @VcmpAuthenticator
        public String authenticate(TokenCredential credential) {
            return null;
        }
    }

    public static class TwoCredentials {
        @VcmpAuthenticator
        public VcmpAuthentication authenticate(TokenCredential credential, String other) {
            return null;
        }
    }

    @Test
    void authenticatorWithoutOptInFailsRegistration() {
        assertThatThrownBy(() -> register(new AuthenticatorWithoutOptIn()))
                .hasMessageContaining("authenticated = true");
        assertThatThrownBy(() -> register(new HandshakeAuthenticatorWithoutOptIn()))
                .hasMessageContaining("authenticated = true");
    }

    @Test
    void optInWithoutAuthenticatorFailsRegistration() {
        assertThatThrownBy(() -> register(new OptInWithoutAuthenticator()))
                .hasMessageContaining("no @VcmpAuthenticator");
    }

    @Test
    void invalidAuthenticatorSignaturesFail() {
        assertThatThrownBy(() -> new VcmpHandler(new WrongReturnType()).requireAuthentication(Duration.ofSeconds(1)))
                .hasMessageContaining("must return a VcmpAuthentication");
        assertThatThrownBy(() -> new VcmpHandler(new TwoCredentials()).requireAuthentication(Duration.ofSeconds(1)))
                .hasMessageContaining("only one credential parameter");
    }

    @Test
    void answerTimeoutBelowOneSecondIsRejected() {
        assertThatThrownBy(() -> new VcmpClientAuthentication(() -> null, Duration.ofMillis(999), problem -> {
        }, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new VcmpClientAuthentication(() -> null, Duration.ofSeconds(1), problem -> {
        }, null).answerTimeout()).isEqualTo(Duration.ofSeconds(1));
    }

    private static void register(Object endpoint) {
        val context = mock(ApplicationContext.class);
        when(context.getBeansWithAnnotation(VcmpEndpoint.class)).thenReturn(Map.of("endpoint", endpoint));
        val registry = mock(WebSocketHandlerRegistry.class);
        val registration = mock(WebSocketHandlerRegistration.class, RETURNS_SELF);
        when(registry.addHandler(any(), anyString())).thenReturn(registration);
        new VcmpServerAutoConfiguration(context, new MockEnvironment()).registerWebSocketHandlers(registry);
    }
}
