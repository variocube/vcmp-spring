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
import org.springframework.web.ErrorResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
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

        /** Run by the failure hook / connected hook, e.g. to call reauthenticate() from inside a hook. */
        volatile Runnable onFailure = () -> {
        };
        volatile Runnable onConnected = () -> {
        };

        @VcmpAuthenticationFailed
        public void authenticationFailed(ProblemDetail problemDetail) {
            failures.add(problemDetail);
            onFailure.run();
        }

        @Override
        public void handleSessionConnected(VcmpSession session) {
            connectedCalls.incrementAndGet();
            super.handleSessionConnected(session);
            onConnected.run();
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
        runId = fake.startRun();
    }

    /**
     * This test's run id. The fake server records only sessions whose URL carries it: a handshake of a previous test
     * that lands late (its manager was already stopped) must not become this test's first session.
     */
    private String runId;

    private String fakeUrl(String pathAndQuery) {
        return BASE_URL + pathAndQuery + (pathAndQuery.contains("?") ? "&" : "?") + "run=" + runId;
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
        val client = start(fakeUrl("/fake/ack?delay=400&push=1"), AutClientTest::token);
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
        val client = start(fakeUrl("/fake/silent"), AutClientTest::token);
        manager.setAuthTimeout(Duration.ofSeconds(1));
        manager.start();

        await().until(() -> client.failures.size() >= 1);
        assertThat(client.failures.get(0).getStatus()).isEqualTo(504);
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
        val client = start(fakeUrl("/fake/nak"), AutClientTest::token);
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
        val client = start(fakeUrl("/fake/ack"), call -> "just a string");
        manager.start();

        await().until(() -> !client.failures.isEmpty());
        assertThat(client.failures.get(0).getStatus()).isEqualTo(500);
        assertThat(fake.framesOf("AUT")).isEmpty();
    }

    @Test
    void failingCredentialSupplierIsAnAuthenticationError() {
        val client = start(fakeUrl("/fake/ack"), call -> {
            if (call == 1) {
                throw new IllegalStateException("Identity provider down");
            }
            return token(call);
        });
        manager.start();

        await().until(client::isConnected);
        assertThat(client.failures).hasSize(1).allMatch(problem -> problem.getStatus() == 500);
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
        manager = new VcmpConnectionManager(client, fakeUrl("/fake/ack"));
        manager.setReconnectTimeoutMin(Duration.ZERO);
        manager.setReconnectTimeoutMax(Duration.ofMillis(1));
        manager.setDisconnectTimeout(Duration.ZERO);
        manager.start();

        await().until(client::isConnected);
        assertThat(client.failures).hasSize(1).allMatch(problem -> problem.getStatus() == 500);
        val firstSession = fake.sessions.get(0);
        assertThat(fake.closeCodes.get(firstSession)).isEqualTo(4008);
        assertThat(fake.closeReasons.get(firstSession)).isEqualTo("authentication failed");
        assertThat(fake.framesOf("AUT")).hasSize(1)
                .allMatch(frame -> frame.payload().equals("{\"token\":\"t2\"}"));
    }

    /** #34: a NAK 503 (server overloaded) is handled like any NAK. */
    @Test
    void nak503ToTheFirstAutClosesAndReconnects() {
        val client = start(fakeUrl("/fake/nak?status=503&keep=1"), AutClientTest::token);
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

    /** The answer timeout starts when the credential is requested: a supplier that never completes ends like an
     * unanswered AUT. */
    @Test
    void credentialSupplierThatNeverCompletesEndsInTheAnswerTimeout() {
        val client = start(fakeUrl("/fake/ack"), call -> call == 1 ? new CompletableFuture<>() : token(call));
        manager.setAuthTimeout(Duration.ofSeconds(1));
        manager.start();

        await().atMost(Duration.ofSeconds(5)).until(client::isConnected);
        assertThat(client.failures).hasSize(1).allMatch(problem -> problem.getStatus() == 504);
        val firstSession = fake.sessions.get(0);
        assertThat(fake.closeCodes.get(firstSession)).isEqualTo(4008);
        assertThat(fake.closeReasons.get(firstSession)).isEqualTo("authentication timeout");
        assertThat(fake.framesOf("AUT")).hasSize(1)
                .allMatch(frame -> frame.payload().equals("{\"token\":\"t2\"}"));
    }

    @Test
    void malformedAckIsAcceptedWithoutExpiry(CapturedOutput output) {
        for (val raw : new String[]{"array", "negative", "fraction", "string"}) {
            fake.clear();
            val client = start(fakeUrl("/fake/ack?malformed=" + raw), AutClientTest::token);
            manager.start();
            await().until(client::isConnected);
            assertThat(client.session().getAuthenticationExpiresAt()).as(raw).isNull();
            stop();
        }
        assertThat(output).contains("is no JSON object; treating it as no payload")
                .contains("has an invalid expiresIn; treating it as absent");
    }

    @Test
    void answerTimeoutBelowOneSecondIsRejected() {
        val manager = new VcmpConnectionManager(new Client(AutClientTest::token), BASE_URL);
        assertThatThrownBy(() -> manager.setAuthTimeout(Duration.ofMillis(999)))
                .isInstanceOf(IllegalArgumentException.class);
        manager.setAuthTimeout(Duration.ofSeconds(1));
    }

    // --- Re-authentication: triggered by the application, the client schedules nothing (#34, 2026-11-26)

    @Test
    void clientSchedulesNoReauthentication() {
        val client = start(fakeUrl("/fake/ack?expiresIn=1500"), AutClientTest::token);
        manager.start();

        await().until(client::isConnected);
        assertThat(client.session().getAuthenticationExpiresAt()).isNotNull();
        assertThat(client.session().getAuthenticationDetails()).containsEntry("expiresIn", 1500)
                .containsEntry("who", "fake");
        sleep(2500); // well beyond the expiry: no timer sends anything
        assertThat(fake.framesOf("AUT")).hasSize(1);
        assertThat(client.credentialCalls.get()).isEqualTo(1);
    }

    @Test
    void reauthenticateSendsOneAutAndCompletesWithTheAckPayload() throws Exception {
        val client = start(fakeUrl("/fake/ack?expiresIn=60000"), AutClientTest::token);
        manager.start();
        await().until(client::isConnected);
        val session = client.session();

        val result = client.reauthenticate().get(3, TimeUnit.SECONDS);

        assertThat(result).containsEntry("expiresIn", 60000).containsEntry("who", "fake");
        assertThat(fake.framesOf("AUT")).hasSize(2);
        val reauthentication = fake.framesOf("AUT").get(1);
        assertThat(reauthentication.payload()).isEqualTo("{\"token\":\"t2\"}");
        assertThat(reauthentication.session()).isEqualTo(fake.sessions.get(0));
        assertThat(client.session()).isSameAs(session);
        assertThat(client.connectedCalls.get()).isEqualTo(1);
        assertThat(client.failures).isEmpty();
    }

    @Test
    void reauthenticationNakIsReportedAndKeepsTheSessionWithoutRetry() {
        for (val status : new int[]{401, 403, 503}) {
            fake.clear();
            val client = start(fakeUrl("/fake/refresh-nak?status=" + status + "&expiresIn=60000"),
                    AutClientTest::token);
            manager.start();
            await().until(client::isConnected);

            val error = reauthenticationError(client.reauthenticate());

            assertThat(error.getStatusCode().value()).as("status").isEqualTo(status);
            // The failure hook runs right after the future completes.
            await().untilAsserted(() -> assertThat(client.failures).hasSize(1).allMatch(problem -> problem.getStatus() == status));
            sleep(1500); // no automatic retry
            assertThat(fake.framesOf("AUT")).as("AUTs after a %s", status).hasSize(2);
            assertThat(client.isConnected()).isTrue();
            assertThat(fake.closed).doesNotContain(fake.framesOf("AUT").get(0).session());
            stop();
        }
    }

    @Test
    void reauthenticationWithAFailingCredentialMethodSendsNothingAndKeepsTheSession() throws Exception {
        val client = start(fakeUrl("/fake/ack?expiresIn=60000"), call -> {
            if (call == 2) {
                throw new IllegalStateException("Identity provider down");
            }
            return token(call);
        });
        manager.start();
        await().until(client::isConnected);

        val error = reauthenticationError(client.reauthenticate());

        assertThat(error.getStatusCode().value()).isEqualTo(500);
        assertThat(error.getBody().getTitle()).isEqualTo("Credential unavailable");
        // The failure hook runs right after the future completes.
        await().untilAsserted(() -> assertThat(client.failures).hasSize(1));
        assertThat(fake.framesOf("AUT")).hasSize(1);
        assertThat(client.isConnected()).isTrue();
        assertThat(fake.closed).doesNotContain(fake.framesOf("AUT").get(0).session());
        // The application may call again.
        assertThat(client.reauthenticate().get(3, TimeUnit.SECONDS)).containsEntry("who", "fake");
        assertThat(fake.framesOf("AUT")).hasSize(2)
                .last().matches(frame -> frame.payload().equals("{\"token\":\"t3\"}"));
    }

    @Test
    void reauthenticationWithACredentialMethodThatDoesNotCompleteSendsNothingAndKeepsTheSession() {
        val client = start(fakeUrl("/fake/ack?expiresIn=60000"),
                call -> call == 2 ? new CompletableFuture<>() : token(call));
        manager.setAuthTimeout(Duration.ofSeconds(1));
        manager.start();
        await().until(client::isConnected);

        val error = reauthenticationError(client.reauthenticate());

        assertThat(error.getStatusCode().value()).isEqualTo(504);
        // The failure hook runs right after the future completes.
        await().untilAsserted(() -> assertThat(client.failures).hasSize(1).allMatch(problem -> problem.getStatus() == 504));
        sleep(300);
        assertThat(fake.framesOf("AUT")).hasSize(1);
        assertThat(client.isConnected()).isTrue();
        assertThat(fake.closed).doesNotContain(fake.framesOf("AUT").get(0).session());
    }

    @Test
    void unansweredReauthenticationClosesTheSessionAndReconnectsWithAFreshCredential() {
        val client = start(fakeUrl("/fake/refresh-silent?expiresIn=60000"), AutClientTest::token);
        manager.setAuthTimeout(Duration.ofSeconds(1));
        manager.start();
        await().until(client::isConnected);
        val firstSession = fake.sessions.get(0);

        val error = reauthenticationError(client.reauthenticate());

        assertThat(error.getStatusCode().value()).isEqualTo(504);
        // The failure hook runs right after the future completes.
        await().untilAsserted(() -> assertThat(client.failures).hasSize(1).allMatch(problem -> problem.getStatus() == 504));
        await().until(() -> fake.closed.contains(firstSession));
        assertThat(fake.closedByServerAt).doesNotContainKey(firstSession);
        assertThat(fake.closeCodes.get(firstSession)).isEqualTo(4008);
        assertThat(fake.closeReasons.get(firstSession)).isEqualTo("authentication timeout");
        // Reconnected, with a fresh credential.
        await().until(() -> fake.framesOf("AUT").stream().anyMatch(frame -> !frame.session().equals(firstSession)));
        val reconnectAut = fake.framesOf("AUT").stream()
                .filter(frame -> !frame.session().equals(firstSession))
                .findFirst().orElseThrow();
        assertThat(reconnectAut.payload()).isEqualTo("{\"token\":\"t3\"}");
    }

    /**
     * #34: the session closing while the AUT is pending is a 503, on connect and on a re-authentication, reported to
     * {@code @VcmpAuthenticationFailed}. Like an old server that closes on the unknown frame: the application must learn
     * why it never gets connected.
     */
    @Test
    void sessionClosingWhileTheFirstAutIsPendingIsA503() {
        val client = start(fakeUrl("/fake/close"), AutClientTest::token);
        manager.start();

        await().until(() -> client.failures.size() >= 1);
        assertThat(client.failures.get(0).getStatus()).isEqualTo(503);
        assertThat(client.connectedCalls.get()).isZero();
        // Reconnects with a fresh credential.
        await().until(() -> fake.framesOf("AUT").size() >= 2);
        assertThat(fake.framesOf("AUT").get(1).payload()).isEqualTo("{\"token\":\"t2\"}");
    }

    @Test
    void sessionClosingWhileAReauthenticationIsPendingFailsItWith503() {
        val client = start(fakeUrl("/fake/refresh-close?expiresIn=60000"), AutClientTest::token);
        manager.setAuthTimeout(Duration.ofSeconds(10));
        manager.start();
        await().until(client::isConnected);

        val started = System.currentTimeMillis();
        val error = reauthenticationError(client.reauthenticate());

        assertThat(error.getStatusCode().value()).isEqualTo(503);
        assertThat(System.currentTimeMillis() - started).as("not waiting for the answer timeout").isLessThan(5000);
        // The failure hook runs right after the future completes.
        await().untilAsserted(() -> assertThat(client.failures).hasSize(1).allMatch(problem -> problem.getStatus() == 503));
        // Reconnects as after any close.
        await().until(() -> fake.sessions.size() >= 2);
    }

    @Test
    void sessionClosingWhileTheCredentialIsRequestedFailsTheReauthenticationWith503() {
        val client = start(fakeUrl("/fake/ack?expiresIn=60000"),
                call -> call == 2 ? new CompletableFuture<>() : token(call));
        manager.setAuthTimeout(Duration.ofSeconds(10));
        manager.start();
        await().until(client::isConnected);

        val future = client.reauthenticate();
        sleep(200);
        stop();
        val started = System.currentTimeMillis();
        val error = reauthenticationError(future);

        assertThat(error.getStatusCode().value()).isEqualTo(503);
        assertThat(System.currentTimeMillis() - started).as("not waiting for the answer timeout").isLessThan(5000);
        assertThat(fake.framesOf("AUT")).hasSize(1);
        // The failure hook runs right after the future completes.
        await().untilAsserted(() -> assertThat(client.failures).hasSize(1).allMatch(problem -> problem.getStatus() == 503));
    }

    /**
     * A hook that calls reauthenticate() while other calls are queued (vcmp-js review): none completes with null
     * although an AUT is sent. The hook runs after the first AUT is over and the queued call's AUT (t2) has started, so
     * its own call is served by a further AUT (t3) with a fresh credential.
     */
    @Test
    void reauthenticateCalledFromTheConnectedHookJoinsTheQueuedCalls() throws Exception {
        val client = start(fakeUrl("/fake/ack?delay=500&expiresIn=60000"), AutClientTest::token);
        val fromHook = new CompletableFuture<CompletableFuture<Map<String, Object>>>();
        client.onConnected = () -> fromHook.complete(client.reauthenticate());
        manager.start();

        await().until(() -> fake.framesOf("AUT").size() == 1);
        val queued = manager.reauthenticate(); // while the first AUT is pending

        assertThat(queued.get(3, TimeUnit.SECONDS)).containsEntry("who", "fake");
        assertThat(fromHook.get(3, TimeUnit.SECONDS).get(3, TimeUnit.SECONDS)).containsEntry("who", "fake");
        sleep(700);
        assertThat(fake.framesOf("AUT")).extracting(FakeAutServer.Frame::payload)
                .containsExactly("{\"token\":\"t1\"}", "{\"token\":\"t2\"}", "{\"token\":\"t3\"}");
    }

    /** R2: the failure hook runs on a VCMP worker, not on the thread that completes the credential method's stage. */
    @Test
    void failureHookRunsOnAVcmpWorkerNotOnTheCredentialMethodsThread() throws Exception {
        val tokenClient = java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "token-client"));
        try {
            val client = start(fakeUrl("/fake/ack"), call -> call == 1
                    ? CompletableFuture.supplyAsync(() -> {
                        throw new IllegalStateException("Identity provider down");
                    }, tokenClient)
                    : token(call));
            val hookThread = new CompletableFuture<String>();
            client.onFailure = () -> hookThread.complete(Thread.currentThread().getName());
            manager.start();

            assertThat(hookThread.get(3, TimeUnit.SECONDS)).startsWith("VCMP-Worker");
        }
        finally {
            tokenClient.shutdownNow();
        }
    }

    /** R1: a client bean that is an AOP proxy (@Async, @Transactional) re-authenticates too. */
    @Test
    void reauthenticateWorksOnAnAopProxiedClient() throws Exception {
        val target = new Client(AutClientTest::token);
        val factory = new org.springframework.aop.framework.ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> invocation.proceed());
        val proxy = (Client) factory.getProxy();
        manager = new VcmpConnectionManager(proxy, fakeUrl("/fake/ack?expiresIn=60000"));
        manager.setReconnectTimeoutMin(Duration.ZERO);
        manager.setReconnectTimeoutMax(Duration.ofMillis(1));

        // Before start: no session, so null rather than "not connected by a VcmpConnectionManager".
        assertThat(proxy.reauthenticate().get(1, TimeUnit.SECONDS)).isNull();

        manager.start();
        await().until(proxy::isConnected);
        assertThat(proxy.reauthenticate().get(3, TimeUnit.SECONDS)).containsEntry("who", "fake");
        assertThat(fake.framesOf("AUT")).hasSize(2);
    }

    /** A hook may block on reauthenticate(): the natural `.join()` in a Spring handler must not deadlock. */
    @Test
    void connectedHookBlockingOnReauthenticateDoesNotDeadlock() throws Exception {
        val client = start(fakeUrl("/fake/ack?expiresIn=60000"), AutClientTest::token);
        val fromHook = new CompletableFuture<Map<String, Object>>();
        client.onConnected = () -> {
            try {
                fromHook.complete(client.reauthenticate().get(5, TimeUnit.SECONDS));
            }
            catch (Exception e) {
                fromHook.completeExceptionally(e);
            }
        };
        manager.start();

        assertThat(fromHook.get(4, TimeUnit.SECONDS)).containsEntry("who", "fake");
    }

    @Test
    void failureHookBlockingOnReauthenticateDoesNotDeadlock() throws Exception {
        val client = start(fakeUrl("/fake/refresh-nak-once?expiresIn=60000"), AutClientTest::token);
        val fromHook = new CompletableFuture<Map<String, Object>>();
        client.onFailure = () -> {
            try {
                fromHook.complete(client.reauthenticate().get(5, TimeUnit.SECONDS));
            }
            catch (Exception e) {
                fromHook.completeExceptionally(e);
            }
        };
        manager.start();
        await().until(client::isConnected);

        reauthenticationError(client.reauthenticate()); // the second AUT is NAKed; the hook blocks on a third
        assertThat(fromHook.get(4, TimeUnit.SECONDS)).containsKey("expiresIn");
    }

    /** R4: an untimed join() in the failure hook completes, and the client keeps re-authenticating afterwards. */
    @Test
    void failureHookJoiningReauthenticateCompletesAndLaterCallsStillWork() throws Exception {
        val client = start(fakeUrl("/fake/refresh-nak-once?expiresIn=60000"), AutClientTest::token);
        val fromHook = new CompletableFuture<Map<String, Object>>();
        client.onFailure = () -> {
            if (!fromHook.isDone()) {
                fromHook.complete(client.reauthenticate().join()); // untimed, as natural in a Spring handler
            }
        };
        manager.start();
        await().until(client::isConnected);

        reauthenticationError(client.reauthenticate()); // the second AUT is NAKed; the hook joins a third

        assertThat(fromHook.get(4, TimeUnit.SECONDS)).containsKey("expiresIn"); // the timeout guard
        assertThat(client.reauthenticate().get(4, TimeUnit.SECONDS)).containsKey("expiresIn");
        assertThat(fake.framesOf("AUT")).hasSize(4);
    }

    @Test
    void reauthenticateCalledFromTheFailureHookGetsTheOutcomeOfTheAutItCauses() throws Exception {
        val client = start(fakeUrl("/fake/refresh-nak-once?expiresIn=60000"), AutClientTest::token);
        val fromHook = new CompletableFuture<CompletableFuture<Map<String, Object>>>();
        client.onFailure = () -> fromHook.complete(client.reauthenticate());
        manager.start();
        await().until(client::isConnected);

        reauthenticationError(client.reauthenticate()); // the second AUT is NAKed

        assertThat(fromHook.get(3, TimeUnit.SECONDS).get(3, TimeUnit.SECONDS)).isNotNull().containsKey("expiresIn");
        assertThat(fake.framesOf("AUT")).hasSize(3);
    }

    @Test
    void callsWhileAnAutIsPendingCoalesceIntoOneFurtherAut() throws Exception {
        val client = start(fakeUrl("/fake/ack?delay=500&expiresIn=60000"), AutClientTest::token);
        manager.start();

        // While the first AUT is pending (its ACK takes 500 ms): two calls, served by one AUT after the ACK.
        await().until(() -> fake.framesOf("AUT").size() == 1);
        val duringFirst = List.of(manager.reauthenticate(), manager.reauthenticate());
        assertThat(duringFirst).noneMatch(CompletableFuture::isDone);
        for (val future : duringFirst) {
            assertThat(future.get(3, TimeUnit.SECONDS)).containsEntry("who", "fake");
        }
        assertThat(fake.framesOf("AUT")).hasSize(2);
        assertThat(fake.framesOf("AUT").get(1).payload()).isEqualTo("{\"token\":\"t2\"}");

        // While a re-authentication is pending: the second and third call share one further AUT.
        val first = client.reauthenticate();
        val later = List.of(client.reauthenticate(), client.reauthenticate());
        assertThat(first.get(3, TimeUnit.SECONDS)).containsEntry("who", "fake");
        assertThat(later).noneMatch(CompletableFuture::isDone);
        for (val future : later) {
            assertThat(future.get(3, TimeUnit.SECONDS)).containsEntry("who", "fake");
        }
        sleep(700);
        assertThat(fake.framesOf("AUT")).extracting(FakeAutServer.Frame::payload).containsExactly(
                "{\"token\":\"t1\"}", "{\"token\":\"t2\"}", "{\"token\":\"t3\"}", "{\"token\":\"t4\"}");
        // One AUT in flight at a time: each was sent after the previous one's ACK (500 ms each).
        val auts = fake.framesOf("AUT");
        for (int i = 1; i < auts.size(); i++) {
            assertThat(auts.get(i).at() - auts.get(i - 1).at()).isGreaterThanOrEqualTo(450L);
        }
    }

    @Test
    void withoutAnAuthenticatedSessionNothingIsSent() throws Exception {
        val client = start(fakeUrl("/fake/ack"), AutClientTest::token);
        // Not started.
        assertThat(client.reauthenticate().get(1, TimeUnit.SECONDS)).isNull();

        manager.start();
        await().until(client::isConnected);
        manager.stop();
        await().until(() -> !client.isConnected());
        // Disconnected.
        assertThat(client.reauthenticate().get(1, TimeUnit.SECONDS)).isNull();
        assertThat(fake.framesOf("AUT")).hasSize(1);
        assertThat(client.credentialCalls.get()).isEqualTo(1);
        assertThat(client.failures).isEmpty();
    }

    @Test
    void reauthenticateWithoutACredentialMethodFails() {
        val client = new BasicVcmpClient();
        new VcmpConnectionManager(client, BASE_URL + "/connect");
        assertThatThrownBy(() -> client.reauthenticate().get(1, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void connectionErrorIsClearedOnceAuthenticatedAgain() throws Exception {
        val client = start(fakeUrl("/fake/refresh-nak-once?expiresIn=60000"), AutClientTest::token);
        manager.start();
        await().until(client::isConnected);

        reauthenticationError(client.reauthenticate());
        assertThat(manager.getConnectionError()).isNotNull();
        client.reauthenticate().get(3, TimeUnit.SECONDS);
        assertThat(manager.getConnectionError()).isNull();
        assertThat(fake.sessions).hasSize(1);
    }

    /** End to end against this library's server: the application re-authenticates before the expiry. */
    @Test
    void reauthenticationAgainstTheServerMovesTheExpiry() throws Exception {
        val client = start(BASE_URL + AutEndpoint.PATH, call -> Map.of("token", "zoe@1500"));
        manager.start();

        await().until(client::isConnected);
        val session = client.session();
        assertThat(session.getAuthenticationDetails()).containsEntry("user", "zoe");
        assertThat(client.send(new Hello("hi"), String.class).await()).isEqualTo("hello zoe");

        sleep(800);
        val result = client.reauthenticate().get(3, TimeUnit.SECONDS);
        assertThat(((Number) result.get("expiresIn")).longValue()).isBetween(1000L, 1500L);

        sleep(1200); // beyond the first expiry (1500 ms)
        assertThat(client.session()).isSameAs(session);
        assertThat(client.send(new Hello("still"), String.class).await()).isEqualTo("hello zoe");
        assertThat(autEndpoint.hellos).contains("zoe:hi", "zoe:still");
    }

    /** An application that never re-authenticates loses the session at the expiry and reconnects. */
    @Test
    void withoutReauthenticationTheServerClosesAtTheExpiryAndTheClientReconnects() {
        val client = start(BASE_URL + AutEndpoint.PATH, call -> Map.of("token", "yan@1200"));
        manager.start();

        await().until(client::isConnected);
        val session = client.session();
        await().atMost(Duration.ofSeconds(5)).until(() -> client.connectedCalls.get() == 2);
        assertThat(client.session()).isNotSameAs(session);
        assertThat(client.credentialCalls.get()).isEqualTo(2);
        assertThat(client.failures).isEmpty();
    }

    private static ErrorResponseException reauthenticationError(CompletableFuture<Map<String, Object>> future) {
        val thrown = catchThrowable(() -> future.get(5, TimeUnit.SECONDS));
        assertThat(thrown).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ErrorResponseException.class);
        return (ErrorResponseException) thrown.getCause();
    }

    @SneakyThrows
    private static void sleep(long millis) {
        Thread.sleep(millis);
    }
}
