package com.variocube.vcmp.aut;

import com.variocube.vcmp.VcmpListener;
import com.variocube.vcmp.VcmpSession;
import com.variocube.vcmp.VcmpTestBase;
import com.variocube.vcmp.client.BasicVcmpClient;
import com.variocube.vcmp.client.VcmpAuthenticationFailed;
import com.variocube.vcmp.client.VcmpConnectionManager;
import com.variocube.vcmp.client.VcmpCredential;
import lombok.SneakyThrows;
import lombok.val;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ProblemDetail;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The client rules of session authentication (vcmp-spring#34), against a hand-written fake server
 * ({@link FakeAutServer}) and, end to end and for contract case 11, against this library's server.
 */
@ExtendWith(OutputCaptureExtension.class)
class AutClientTest extends VcmpTestBase {

    @Autowired
    private FakeAutServer fake;

    @Autowired
    private AutEndpoint autEndpoint;

    private VcmpConnectionManager manager;

    /** A client that authenticates with {@code {"token": tokens(n)}} on its n-th call (1-based). */
    public static class Client extends BasicVcmpClient {
        final AtomicInteger credentialCalls = new AtomicInteger();
        final List<ProblemDetail> failures = new CopyOnWriteArrayList<>();
        final List<ProblemDetail> replyFailures = new CopyOnWriteArrayList<>();
        final AtomicInteger connectedCalls = new AtomicInteger();
        final IntFunction<Object> tokens;

        Client(IntFunction<Object> tokens) {
            this.tokens = tokens;
        }

        /** {@code tokens} may return a credential, a {@code CompletionStage} of one, or throw. */
        @VcmpCredential
        @SuppressWarnings("unchecked")
        public CompletionStage<Object> credential() {
            int call = credentialCalls.incrementAndGet();
            return CompletableFuture.supplyAsync(() -> call).thenCompose(c -> {
                Object value = tokens.apply(c);
                return value instanceof CompletionStage<?> stage
                        ? (CompletionStage<Object>) stage
                        : CompletableFuture.completedFuture(value);
            });
        }

        @VcmpAuthenticationFailed
        public void authenticationFailed(ProblemDetail problemDetail) {
            failures.add(problemDetail);
        }

        @Override
        public void handleSessionConnected(VcmpSession session) {
            connectedCalls.incrementAndGet();
            super.handleSessionConnected(session);
        }

        VcmpSession session() {
            return getSession();
        }

        @VcmpListener
        public void handleHello(Hello hello, VcmpSession session) {
            // A reply from a listener, before the session is authenticated, must not go out.
            session.send(new Hello("reply")).peekNak(replyFailures::add);
        }
    }

    static Object token(int call) {
        return Map.of("token", "t" + call);
    }

    @BeforeEach
    void clear() {
        fake.clear();
    }

    @AfterEach
    @SneakyThrows
    void stop() {
        if (manager != null) {
            manager.stop();
        }
    }

    private Client start(String url, IntFunction<Object> tokens) {
        val client = new Client(tokens);
        manager = new VcmpConnectionManager(client, url);
        manager.setReconnectTimeoutMin(Duration.ZERO);
        manager.setReconnectTimeoutMax(Duration.ofMillis(1));
        manager.setDisconnectTimeout(Duration.ZERO);
        return client;
    }

    @Test
    void sendsAutFirstAndConnectsOnlyAfterTheAck(CapturedOutput output) {
        val client = start(BASE_URL + "/fake/ack?delay=400&push=1", AutClientTest::token);
        manager.start();

        await().until(() -> !fake.framesOf("AUT").isEmpty());
        val aut = fake.frames.get(0);
        assertThat(aut.type()).isEqualTo("AUT");
        assertThat(aut.payload()).isEqualTo("{\"token\":\"t1\"}");

        // Before the ACK: not connected, nothing can be sent, not even a listener's reply.
        assertThat(client.isConnected()).isFalse();
        val nak = new java.util.concurrent.atomic.AtomicReference<ProblemDetail>();
        client.send(new Hello("too early")).peekNak(nak::set);
        assertThat(nak.get().getStatus()).isEqualTo(503);
        await().until(() -> !client.replyFailures.isEmpty());
        assertThat(client.replyFailures.get(0).getTitle()).isEqualTo("Not authenticated");

        await().until(client::isConnected);
        assertThat(client.session().isAuthenticated()).isTrue();
        client.send(new Hello("after ack")).await();

        val ackAt = fake.firstAckAt.values().iterator().next();
        assertThat(fake.framesOf("MSG")).hasSize(1)
                .allMatch(frame -> frame.at() >= ackAt && frame.payload().contains("after ack"));
        assertThat(client.connectedCalls.get()).isEqualTo(1);
        assertThat(client.failures).isEmpty();
        assertThat(output).contains("AUT#").doesNotContain("\"token\":\"t1\"");
    }

    @Test
    void answerTimeoutIsAnAuthenticationErrorAndReconnectsWithAFreshCredential() {
        val client = start(BASE_URL + "/fake/silent", AutClientTest::token);
        manager.setAuthTimeout(Duration.ofSeconds(1));
        manager.start();

        await().until(() -> client.failures.size() >= 1);
        assertThat(client.failures.get(0).getStatus()).isEqualTo(408);
        await().until(() -> fake.closed.contains(fake.sessions.get(0)));
        assertThat(fake.closeCodes.get(fake.sessions.get(0))).isEqualTo(4008);
        assertThat(fake.closeReasons.get(fake.sessions.get(0))).isEqualTo("authentication timeout");

        // Reconnected, with a fresh credential.
        await().until(() -> fake.framesOf("AUT").size() >= 2);
        assertThat(fake.framesOf("AUT").get(1).payload()).isEqualTo("{\"token\":\"t2\"}");
        assertThat(fake.framesOf("AUT").get(1).session()).isNotEqualTo(fake.sessions.get(0));
        assertThat(fake.framesOf("MSG")).isEmpty();
        assertThat(client.connectedCalls.get()).isZero();
    }

    @Test
    void nakIsAnAuthenticationErrorAndReconnectsWithAFreshCredential() {
        val client = start(BASE_URL + "/fake/nak", AutClientTest::token);
        manager.start();

        await().until(() -> fake.framesOf("AUT").size() >= 2);
        assertThat(client.failures.get(0).getStatus()).isEqualTo(401);
        assertThat(fake.framesOf("AUT").get(1).payload()).isEqualTo("{\"token\":\"t2\"}");
        assertThat(client.isConnected()).isFalse();
        assertThat(client.connectedCalls.get()).isZero();
    }

    /** Contract case 11: a client with a credential against an endpoint without the opt-in. */
    @Test
    void endpointWithoutAuthenticationIsAnAuthenticationError() {
        val client = start(BASE_URL + "/connect", AutClientTest::token);
        manager.start();

        await().until(() -> !client.failures.isEmpty());
        assertThat(client.failures.get(0).getStatus()).isEqualTo(400);
        assertThat(client.isConnected()).isFalse();
        assertThat(client.connectedCalls.get()).isZero();
    }

    @Test
    void credentialThatIsNoJsonObjectIsAnAuthenticationError() {
        val client = start(BASE_URL + "/fake/ack", call -> "just a string");
        manager.start();

        await().until(() -> !client.failures.isEmpty());
        assertThat(client.failures.get(0).getStatus()).isEqualTo(400);
        assertThat(fake.framesOf("AUT")).isEmpty();
    }

    @Test
    void failingCredentialSupplierIsAnAuthenticationError() {
        val client = start(BASE_URL + "/fake/ack", call -> {
            if (call == 1) {
                throw new IllegalStateException("Identity provider down");
            }
            return token(call);
        });
        manager.start();

        await().until(client::isConnected);
        assertThat(client.failures).hasSize(1);
        assertThat(fake.framesOf("AUT")).hasSize(1);
        assertThat(fake.framesOf("AUT").get(0).payload()).isEqualTo("{\"token\":\"t2\"}");
        // The first session was closed by the client, without an AUT.
        assertThat(fake.closeCodes.get(fake.sessions.get(0))).isEqualTo(4008);
        assertThat(fake.closeReasons.get(fake.sessions.get(0))).isEqualTo("authentication failed");
        assertThat(manager.getConnectionError()).isNull();
    }

    /** A credential method that throws synchronously (before returning its CompletionStage). */
    public static class SyncThrowingClient extends Client {
        SyncThrowingClient() {
            super(AutClientTest::token);
        }

        @Override
        @VcmpCredential
        public CompletionStage<Object> credential() {
            if (credentialCalls.get() == 0) {
                credentialCalls.incrementAndGet();
                throw new IllegalStateException("Identity provider client not ready");
            }
            return super.credential();
        }
    }

    @Test
    void credentialMethodThrowingSynchronouslyIsAFailedSupplier() {
        val client = new SyncThrowingClient();
        manager = new VcmpConnectionManager(client, BASE_URL + "/fake/ack");
        manager.setReconnectTimeoutMin(Duration.ZERO);
        manager.setReconnectTimeoutMax(Duration.ofMillis(1));
        manager.setDisconnectTimeout(Duration.ZERO);
        manager.start();

        await().until(client::isConnected);
        assertThat(client.failures).hasSize(1).allMatch(problem -> problem.getStatus() == 401);
        val firstSession = fake.sessions.get(0);
        assertThat(fake.closeCodes.get(firstSession)).isEqualTo(4008);
        assertThat(fake.closeReasons.get(firstSession)).isEqualTo("authentication failed");
        assertThat(fake.framesOf("AUT")).hasSize(1)
                .allMatch(frame -> frame.payload().equals("{\"token\":\"t2\"}"));
    }

    /** #34: a NAK 503 (server overloaded) is handled like any NAK. */
    @Test
    void nak503ToTheFirstAutClosesAndReconnects() {
        val client = start(BASE_URL + "/fake/nak?status=503&keep=1", AutClientTest::token);
        manager.start();

        await().until(() -> fake.framesOf("AUT").size() >= 2);
        assertThat(client.failures.get(0).getStatus()).isEqualTo(503);
        val firstSession = fake.sessions.get(0);
        assertThat(fake.closeCodes.get(firstSession)).isEqualTo(4008);
        assertThat(fake.closeReasons.get(firstSession)).isEqualTo("authentication failed");
        assertThat(fake.framesOf("AUT").get(1).session()).isNotEqualTo(firstSession);
        assertThat(fake.framesOf("AUT").get(1).payload()).isEqualTo("{\"token\":\"t2\"}");
        assertThat(client.connectedCalls.get()).isZero();
    }

    @Test
    void nak503ToARefreshIsRetried() {
        val client = start(BASE_URL + "/fake/refresh-nak?status=503&expiresIn=4000", AutClientTest::token);
        manager.setAuthRefreshLeadTime(Duration.ofSeconds(2));
        manager.setAuthRefreshRetryInterval(Duration.ofSeconds(1));
        manager.start();

        await().atMost(Duration.ofSeconds(8)).until(() -> fake.sessions.size() >= 2);
        val firstSession = fake.sessions.get(0);
        val refreshes = fake.framesOf("AUT").stream()
                .filter(frame -> frame.session().equals(firstSession))
                .skip(1)
                .toList();
        assertThat(refreshes).hasSize(2);
        assertThat(client.failures).hasSize(2).allMatch(problem -> problem.getStatus() == 503);
        assertThat(fake.closedByServerAt).containsKey(firstSession);
    }

    /** The answer timeout starts when the credential is requested: a supplier that never completes ends like an
     * unanswered AUT. */
    @Test
    void credentialSupplierThatNeverCompletesEndsInTheAnswerTimeout() {
        val client = start(BASE_URL + "/fake/ack", call -> call == 1 ? new CompletableFuture<>() : token(call));
        manager.setAuthTimeout(Duration.ofSeconds(1));
        manager.start();

        await().atMost(Duration.ofSeconds(5)).until(client::isConnected);
        assertThat(client.failures).hasSize(1).allMatch(problem -> problem.getStatus() == 408);
        val firstSession = fake.sessions.get(0);
        assertThat(fake.closeCodes.get(firstSession)).isEqualTo(4008);
        assertThat(fake.closeReasons.get(firstSession)).isEqualTo("authentication timeout");
        assertThat(fake.framesOf("AUT")).hasSize(1)
                .allMatch(frame -> frame.payload().equals("{\"token\":\"t2\"}"));
    }

    @Test
    void connectionErrorIsClearedOnceAuthenticatedAgain() {
        val client = start(BASE_URL + "/fake/refresh-nak-once?expiresIn=4000", AutClientTest::token);
        manager.setAuthRefreshLeadTime(Duration.ofSeconds(2));
        manager.setAuthRefreshRetryInterval(Duration.ofSeconds(1));
        manager.start();

        // Refresh at ~2 s is NAKed, the retry at ~3 s succeeds.
        await().atMost(Duration.ofSeconds(5)).until(() -> !client.failures.isEmpty());
        assertThat(manager.getConnectionError()).isNotNull();
        await().atMost(Duration.ofSeconds(5)).until(() -> fake.framesOf("AUT").size() >= 3);
        await().until(() -> manager.getConnectionError() == null);
        assertThat(fake.sessions).hasSize(1);
        assertThat(client.isConnected()).isTrue();
    }

    @Test
    void malformedAckIsAcceptedWithoutExpiry(CapturedOutput output) {
        for (val raw : new String[]{"array", "negative", "fraction", "string"}) {
            fake.clear();
            val client = start(BASE_URL + "/fake/ack?malformed=" + raw, AutClientTest::token);
            manager.start();
            await().until(client::isConnected);
            assertThat(client.session().getAuthenticationExpiresAt()).as(raw).isNull();
            stop();
        }
        assertThat(output).contains("is no JSON object; treating it as no payload")
                .contains("has an invalid expiresIn; treating it as absent");
    }

    @Test
    void timingsBelowOneSecondAreRejected() {
        val manager = new VcmpConnectionManager(new Client(AutClientTest::token), BASE_URL);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> manager.setAuthTimeout(Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> manager.setAuthRefreshRetryInterval(Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> manager.setAuthRefreshLeadTime(Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refreshesBeforeTheExpiryWithTheDefaultLeadTime() {
        val client = start(BASE_URL + "/fake/ack?expiresIn=2000", AutClientTest::token);
        manager.start();

        await().until(() -> fake.framesOf("AUT").size() >= 2);
        val firstAck = fake.firstAckAt.get(fake.sessions.get(0));
        val refresh = fake.framesOf("AUT").get(1);
        // Default lead time: min(60 s, 20 % of 2000 ms) = 400 ms before the expiry.
        assertThat(refresh.at() - firstAck).isBetween(1500L, 1900L);
        assertThat(refresh.session()).isEqualTo(fake.sessions.get(0));
        assertThat(refresh.payload()).isEqualTo("{\"token\":\"t2\"}");
        assertThat(client.isConnected()).isTrue();
        assertThat(client.connectedCalls.get()).isEqualTo(1);
        assertThat(client.session().getAuthenticationDetails()).containsEntry("who", "fake");
    }

    @Test
    void refreshLeadTimeIsConfigurable() {
        start(BASE_URL + "/fake/ack?expiresIn=3000", AutClientTest::token);
        manager.setAuthRefreshLeadTime(Duration.ofMillis(1200));
        manager.start();

        await().until(() -> fake.framesOf("AUT").size() >= 2);
        val firstAck = fake.firstAckAt.get(fake.sessions.get(0));
        assertThat(fake.framesOf("AUT").get(1).at() - firstAck).isBetween(1700L, 2100L);
    }

    @Test
    void refreshLeadIsAtMostHalfOfExpiresIn() {
        start(BASE_URL + "/fake/ack?expiresIn=3000", AutClientTest::token);
        manager.setAuthRefreshLeadTime(Duration.ofSeconds(60));
        manager.start();

        await().until(() -> fake.framesOf("AUT").size() >= 2);
        val firstAck = fake.firstAckAt.get(fake.sessions.get(0));
        assertThat(fake.framesOf("AUT").get(1).at() - firstAck).isBetween(1400L, 1800L);
    }

    @Test
    void refreshIsNeverSoonerThanOneSecondAfterTheAck() {
        start(BASE_URL + "/fake/ack?expiresIn=300", AutClientTest::token);
        manager.start();

        await().until(() -> fake.framesOf("AUT").size() >= 2);
        val firstAck = fake.firstAckAt.get(fake.sessions.get(0));
        assertThat(fake.framesOf("AUT").get(1).at() - firstAck).isBetween(950L, 1400L);
    }

    @Test
    void failedRefreshIsRetriedUntilTheExpiry() {
        val client = start(BASE_URL + "/fake/refresh-nak?expiresIn=4000", AutClientTest::token);
        manager.setAuthRefreshLeadTime(Duration.ofSeconds(2));
        manager.setAuthRefreshRetryInterval(Duration.ofSeconds(1));
        manager.start();

        // Closed by the server at the expiry, then reconnected.
        await().atMost(Duration.ofSeconds(8)).until(() -> fake.sessions.size() >= 2);
        val firstSession = fake.sessions.get(0);
        val firstAck = fake.firstAckAt.get(firstSession);
        val refreshes = fake.framesOf("AUT").stream()
                .filter(frame -> frame.session().equals(firstSession))
                .skip(1)
                .toList();
        // Lead time 2 s: first refresh at ~2 s, then every 1 s while before the expiry (~3 s; not at 4 s).
        assertThat(refreshes).hasSize(2);
        assertThat(refreshes).allMatch(frame -> frame.at() - firstAck < 4000);
        assertThat(refreshes.get(1).at() - refreshes.get(0).at()).isBetween(900L, 1500L);
        assertThat(client.failures).allMatch(problem -> problem.getStatus() == 401);
        // The session ended because the server closed it at the expiry, not because the client gave up.
        assertThat(fake.closedByServerAt).containsKey(firstSession);
    }

    @Test
    void unansweredRefreshClosesTheSessionAndReconnectsWithAFreshCredential() {
        val client = start(BASE_URL + "/fake/refresh-silent?expiresIn=4000", AutClientTest::token);
        manager.setAuthTimeout(Duration.ofSeconds(1));
        manager.setAuthRefreshLeadTime(Duration.ofSeconds(2));
        manager.start();

        await().atMost(Duration.ofSeconds(8)).until(() -> fake.sessions.size() >= 2);
        val firstSession = fake.sessions.get(0);
        val refreshes = fake.framesOf("AUT").stream()
                .filter(frame -> frame.session().equals(firstSession))
                .skip(1)
                .toList();
        // Lead 2 s: refresh at ~2 s, no answer by ~3 s -> closed by the client, before the expiry at 4 s.
        assertThat(refreshes).hasSize(1);
        assertThat(client.failures).hasSize(1).allMatch(problem -> problem.getStatus() == 408);
        assertThat(fake.closedByServerAt).doesNotContainKey(firstSession);
        assertThat(fake.closeCodes.get(firstSession)).isEqualTo(4008);
        assertThat(fake.closeReasons.get(firstSession)).isEqualTo("authentication timeout");
        // Reconnected, with a fresh credential.
        await().until(() -> fake.framesOf("AUT").stream()
                .anyMatch(frame -> !frame.session().equals(firstSession)));
        val reconnectAut = fake.framesOf("AUT").stream()
                .filter(frame -> !frame.session().equals(firstSession))
                .findFirst().orElseThrow();
        assertThat(reconnectAut.payload()).isEqualTo("{\"token\":\"t3\"}");
    }

    @Test
    void failedRefreshIsRetriedEveryFiveSecondsByDefault() {
        assertThat(new VcmpConnectionManager(new Client(AutClientTest::token), BASE_URL)
                .getAuthRefreshRetryInterval()).isEqualTo(Duration.ofSeconds(5));
    }

    /** End to end against this library's server: the session survives its first expiry thanks to the refresh. */
    @Test
    void authenticatesAndRefreshesAgainstTheServer() {
        val client = start(BASE_URL + AutEndpoint.PATH, call -> Map.of("token", "zoe@1500"));
        manager.start();

        await().until(client::isConnected);
        val session = client.session();
        assertThat(session.getAuthenticationDetails()).containsEntry("user", "zoe");
        assertThat(session.getAuthenticationExpiresAt()).isNotNull();
        assertThat(client.send(new Hello("hi"), String.class).await()).isEqualTo("hello zoe");

        // Refresh at ~1200 ms (lead 300 ms): the session outlives its first expiry at 1500 ms.
        await().atMost(Duration.ofSeconds(5)).pollDelay(Duration.ofMillis(2000)).until(() -> true);
        assertThat(client.session()).isSameAs(session);
        assertThat(client.credentialCalls.get()).isGreaterThanOrEqualTo(2);
        assertThat(client.send(new Hello("still"), String.class).await()).isEqualTo("hello zoe");
        assertThat(autEndpoint.hellos).contains("zoe:hi", "zoe:still");
    }
}
