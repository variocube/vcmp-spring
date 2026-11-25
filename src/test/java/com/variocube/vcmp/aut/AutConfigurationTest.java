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
    void defaultRefreshLeadTimeIsTheEarlierOf60SecondsAnd20Percent() {
        val defaults = new VcmpClientAuthentication(() -> null, Duration.ofSeconds(10), null, null, problem -> {
        }, null);
        assertThat(defaults.refreshLeadTime()).isNull();
        assertThat(defaults.refreshRetryInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(leadMillis(defaults, 1_000)).isEqualTo(200);
        assertThat(leadMillis(defaults, 300_000)).isEqualTo(60_000);
        assertThat(leadMillis(defaults, 3_600_000)).isEqualTo(60_000);

        val configured = new VcmpClientAuthentication(() -> null, Duration.ofSeconds(10), Duration.ofSeconds(5), null,
                problem -> {
                }, null);
        assertThat(leadMillis(configured, 1_000_000)).isEqualTo(5_000);
    }

    @Test
    void refreshLeadIsAtMostHalfOfExpiresInAndARefreshComesNoSoonerThanOneSecondAfterTheAck() {
        val defaults = new VcmpClientAuthentication(() -> null, Duration.ofSeconds(10), null, null, problem -> {
        }, null);
        assertThat(delayMillis(defaults, 2_000)).isEqualTo(1_600);
        assertThat(delayMillis(defaults, 600_000)).isEqualTo(540_000);
        assertThat(delayMillis(defaults, 1_000)).isEqualTo(1_000); // 800 would be sooner than 1 s
        assertThat(delayMillis(defaults, 0)).isEqualTo(1_000);

        val longLead = new VcmpClientAuthentication(() -> null, Duration.ofSeconds(10), Duration.ofSeconds(5), null,
                problem -> {
                }, null);
        assertThatThrownBy(() -> new VcmpClientAuthentication(() -> null, Duration.ofMillis(999), null, null,
                problem -> {
                }, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VcmpClientAuthentication(() -> null, Duration.ofSeconds(1), null,
                Duration.ofMillis(999), problem -> {
                }, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(leadMillis(longLead, 4_000)).isEqualTo(2_000);
        assertThat(delayMillis(longLead, 4_000)).isEqualTo(2_000);
        assertThat(delayMillis(longLead, 100)).isEqualTo(1_000);
    }

    private static long delayMillis(VcmpClientAuthentication authentication, long expiresIn) {
        return com.variocube.vcmp.VcmpClientAuthenticationAccess.refreshDelayMillis(authentication, expiresIn);
    }

    private static long leadMillis(VcmpClientAuthentication authentication, long expiresIn) {
        return com.variocube.vcmp.VcmpClientAuthenticationAccess.refreshLeadMillis(authentication, expiresIn);
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
