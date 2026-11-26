package com.variocube.vcmp.aut;

import com.fasterxml.jackson.databind.JsonNode;
import com.variocube.vcmp.VcmpSession;
import com.variocube.vcmp.VcmpTestBase;
import lombok.SneakyThrows;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ProblemDetail;
import org.springframework.web.socket.CloseStatus;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.variocube.vcmp.ObjectMapperHolder.OBJECT_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The server rules of session authentication (vcmp-spring#34), driven by a plain WebSocket client sending frames by
 * hand. The numbered cases are those of the cross-implementation contract suite.
 */
@ExtendWith(OutputCaptureExtension.class)
class AutServerTest extends VcmpTestBase {

    static final String URL = BASE_URL + AutEndpoint.PATH;

    @Autowired
    private AutEndpoint endpoint;

    @Autowired
    private HandshakeEndpoint handshakeEndpoint;

    // --- 1. valid AUT -> ACK, then messages flow both ways

    @Test
    void validAutIsAcknowledgedThenMessagesFlowBothWays() {
        try (val client = new RawClient(URL)) {
            client.aut("aut000000001", "alice");
            val ack = client.next();
            assertThat(ack).startsWith("ACKaut000000001");
            assertThat(payload(ack).get("user").asText()).isEqualTo("alice");
            assertThat(payload(ack).has("expiresIn")).isFalse();

            client.hello("msg000000001", "hi");
            assertThat(client.next()).isEqualTo("ACKmsg000000001\"hello alice\"");
            assertThat(endpoint.hellos).contains("alice:hi");

            await().until(() -> endpoint.connected.contains("alice"));
            endpoint.sessionPool.send("alice", new Hello("pushed"));
            val pushed = client.next();
            assertThat(pushed).startsWith("MSG").contains("\"text\":\"pushed\"");
        }
    }

    // --- 2. invalid AUT -> NAK 401 + close 1008

    @Test
    void invalidAutIsNakked401AndClosed() {
        assertFirstAutRejected("bad-token", 401);
    }

    @Test
    void forbiddenAutIsNakked403AndClosed() {
        assertFirstAutRejected("forbidden", 403);
    }

    @Test
    void failingAuthenticatorIsNakked500AndClosed() {
        assertFirstAutRejected("crash", 500);
    }

    private void assertFirstAutRejected(String token, int status) {
        try (val client = new RawClient(URL)) {
            client.aut("aut000000002", token);
            val nak = client.next();
            assertThat(nak).startsWith("NAKaut000000002");
            assertThat(payload(nak).get("status").asInt()).isEqualTo(status);
            assertPolicyViolation(client.awaitClose());
        }
    }

    /** #34 "Synchronous failures": even an Error thrown by the authenticator is a NAK 500, never escaping. */
    @Test
    void authenticatorThrowingAnErrorIsNakked500AndClosed() {
        assertFirstAutRejected("error", 500);
        // The worker pool is still fine.
        try (val client = new RawClient(URL)) {
            client.aut("aut000000049", "quentin");
            assertThat(client.next()).startsWith("ACKaut000000049");
        }
    }

    @Test
    void handshakeAuthenticatorThrowingAnErrorCloses() {
        try (val client = new RawClient(BASE_URL + "/aut-handshake/error")) {
            val close = client.awaitClose();
            assertPolicyViolation(close);
            assertThat(close.getReason()).isEqualTo("authentication failed");
        }
    }

    @Test
    void firstAutWithoutPrincipalIsNakked500AndClosed() {
        assertFirstAutRejected("nullprincipal", 500);
        assertThat(endpoint.connected).doesNotContain("<anonymous>");
    }

    /** Authenticated only once the ACK is out: an ACK that can't be built fails closed, without a connected session. */
    @Test
    void firstAutWhoseAckCannotBeSerializedIsNakked500AndClosed() {
        try (val client = new RawClient(URL)) {
            client.aut("aut000000042", "unserializable:uma");
            val nak = client.next();
            assertThat(nak).startsWith("NAKaut000000042");
            assertThat(payload(nak).get("status").asInt()).isEqualTo(500);
            assertThat(client.awaitClose().getReason()).isEqualTo("authentication failed");
        }
        sleep(200);
        assertThat(endpoint.connected).doesNotContain("uma");
        assertThat(endpoint.disconnected).doesNotContain("uma");
    }

    @Test
    void refreshWhoseAckCannotBeSerializedKeepsTheOldExpiry() {
        try (val client = new RawClient(URL)) {
            val start = System.currentTimeMillis();
            client.aut("aut000000043", "vera@900");
            assertThat(client.next()).startsWith("ACKaut000000043");
            client.aut("aut000000044", "unserializable:vera@5000");
            assertThat(payload(client.next()).get("status").asInt()).isEqualTo(500);
            assertThat(client.isOpen()).isTrue();
            assertThat(client.awaitClose().getReason()).isEqualTo("authentication expired");
            assertThat(System.currentTimeMillis() - start).isBetween(850L, 2500L);
        }
    }

    @Test
    void refreshWhosePrincipalComparisonFailsIsNakked500AndKeepsTheSession() {
        try (val client = new RawClient(URL)) {
            client.aut("aut000000045", "boom@900");
            assertThat(client.next()).startsWith("ACKaut000000045");
            client.aut("aut000000046", "boom@5000");
            assertThat(payload(client.next()).get("status").asInt()).isEqualTo(500);
            client.hello("msg000000046", "still");
            assertThat(client.next()).isEqualTo("ACKmsg000000046\"hello boom\"");
            assertThat(client.awaitClose().getReason()).isEqualTo("authentication expired");
        }
    }

    @Test
    void malformedFirstAutIsNakked400AndClosed() {
        for (val payload : new String[]{"", "[\"token\"]", "\"token\"", "not json", "{\"token\":{\"no\":1}}"}) {
            try (val client = new RawClient(URL)) {
                client.send("AUTaut000000003" + payload);
                val nak = client.next();
                assertThat(nak).as(payload).startsWith("NAKaut000000003");
                assertThat(payload(nak).get("status").asInt()).as(payload).isEqualTo(400);
                assertPolicyViolation(client.awaitClose());
            }
        }
    }

    // --- 3. MSG (or ACK, NAK, garbage) before AUT -> close 1008

    @Test
    void messageBeforeAutCloses() {
        for (val frame : new String[]{
                "MSGmsg000000002{\"@type\":\"aut:Hello\",\"text\":\"sneaky\"}",
                "ACKmsg000000002",
                "NAKmsg000000002",
                "XYZmsg000000002",
        }) {
            try (val client = new RawClient(URL)) {
                client.send(frame);
                val close = client.awaitClose();
                assertPolicyViolation(close);
                assertThat(close.getReason()).isEqualTo("authentication required");
            }
        }
        assertThat(endpoint.hellos).noneMatch(hello -> hello.endsWith(":sneaky"));
    }

    @Test
    void binaryMessageBeforeAutCloses() {
        try (val client = new RawClient(URL)) {
            client.sendBinary(new byte[]{1, 2, 3});
            val close = client.awaitClose();
            assertPolicyViolation(close);
            assertThat(close.getReason()).isEqualTo("authentication required");
        }
    }

    @Test
    void messageWhileFirstAutIsPendingCloses() {
        try (val client = new RawClient(URL)) {
            client.aut("aut000000004", "slow:bert");
            client.hello("msg000000004", "too early");
            assertPolicyViolation(client.awaitClose());
            // Neither ACK nor connected hook for a session closed while it was being authenticated.
            assertThat(client.nextWithin(500)).isNull();
            assertThat(endpoint.connected).doesNotContain("bert");
            assertThat(endpoint.hellos).doesNotContain("bert:too early");
        }
    }

    @Test
    void heartbeatBeforeAutIsAccepted() {
        try (val client = new RawClient(URL)) {
            client.send("HBT100");
            assertThat(client.next()).isEqualTo("HBT100");
            client.aut("aut000000005", "carl");
            assertThat(client.next()).startsWith("ACKaut000000005");
        }
    }

    // --- 4. no AUT within the timeout -> close 1008

    @Test
    void noAutWithinTimeoutCloses() {
        val start = System.currentTimeMillis();
        try (val client = new RawClient(URL)) {
            val close = client.awaitClose();
            assertPolicyViolation(close);
            assertThat(close.getReason()).isEqualTo("authentication timeout");
            assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(AutEndpoint.AUTH_TIMEOUT_MILLIS - 50);
        }
    }

    @Test
    void authenticatedSessionSurvivesTheAuthTimeout() {
        try (val client = new RawClient(URL)) {
            client.aut("aut000000006", "dora");
            assertThat(client.next()).startsWith("ACKaut000000006");
            sleep(AutEndpoint.AUTH_TIMEOUT_MILLIS + 200);
            assertThat(client.isOpen()).isTrue();
        }
    }

    // --- 5. AUT on an endpoint without auth -> NAK 400, session stays

    @Test
    void autOnEndpointWithoutAuthenticationIsNakked400AndSessionStays() {
        try (val client = new RawClient(BASE_URL + "/connect")) {
            client.aut("aut000000007", "alice");
            val nak = client.next();
            assertThat(nak).startsWith("NAKaut000000007");
            assertThat(payload(nak).get("status").asInt()).isEqualTo(400);

            client.send("MSGmsg000000007{\"@type\":\"ChangeFooMessage\",\"newFoo\":\"x\"}");
            assertThat(client.next()).isEqualTo("ACKmsg000000007");
            assertThat(client.isOpen()).isTrue();
        }
    }

    // --- 6. expiry -> close 1008 when expiresIn runs out

    @Test
    void sessionClosesWhenAuthenticationExpires() {
        try (val client = new RawClient(URL)) {
            val start = System.currentTimeMillis();
            client.aut("aut000000008", "emil@500");
            val ack = client.next();
            assertThat(payload(ack).get("expiresIn").asLong()).isBetween(1L, 500L);
            val close = client.awaitClose();
            assertPolicyViolation(close);
            assertThat(close.getReason()).isEqualTo("authentication expired");
            assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(450);
            await().until(() -> endpoint.disconnected.contains("emil"));
        }
    }

    // --- 7. refresh, same principal -> ACK, expiry moved

    @Test
    void refreshWithSamePrincipalMovesExpiry() {
        try (val client = new RawClient(URL)) {
            client.aut("aut000000009", "fred@600");
            assertThat(client.next()).startsWith("ACKaut000000009");
            sleep(300);
            client.aut("aut000000010", "fred@1200");
            val ack = client.next();
            assertThat(ack).startsWith("ACKaut000000010");
            assertThat(payload(ack).get("expiresIn").asLong()).isBetween(1000L, 1200L);

            sleep(600); // beyond the first expiry
            assertThat(client.isOpen()).isTrue();
            client.hello("msg000000010", "still here");
            assertThat(client.next()).isEqualTo("ACKmsg000000010\"hello fred\"");
            assertThat(client.awaitClose().getReason()).isEqualTo("authentication expired");
        }
    }

    @Test
    void refreshWithoutExpiryRemovesIt() {
        try (val client = new RawClient(URL)) {
            client.aut("aut000000011", "gail@400");
            assertThat(client.next()).startsWith("ACKaut000000011");
            client.aut("aut000000012", "gail");
            val ack = client.next();
            assertThat(ack).startsWith("ACKaut000000012");
            assertThat(payload(ack).has("expiresIn")).isFalse();
            sleep(700);
            assertThat(client.isOpen()).isTrue();
        }
    }

    @Test
    void messagesKeepFlowingDuringRefresh() {
        try (val client = new RawClient(URL)) {
            client.aut("aut000000013", "hugo");
            assertThat(client.next()).startsWith("ACKaut000000013");
            client.aut("aut000000014", "slow:hugo");
            client.hello("msg000000014", "meanwhile");
            assertThat(client.next()).isEqualTo("ACKmsg000000014\"hello hugo\"");
            assertThat(client.next()).startsWith("ACKaut000000014");
        }
    }

    // --- 8. refresh, other principal -> NAK 403 + close

    @Test
    void refreshWithOtherPrincipalIsNakked403AndCloses() {
        try (val client = new RawClient(URL)) {
            client.aut("aut000000015", "ines");
            assertThat(client.next()).startsWith("ACKaut000000015");
            client.aut("aut000000016", "mallory");
            val nak = client.next();
            assertThat(nak).startsWith("NAKaut000000016");
            assertThat(payload(nak).get("status").asInt()).isEqualTo(403);
            val close = client.awaitClose();
            assertPolicyViolation(close);
            assertThat(close.getReason()).isEqualTo("principal changed");
        }
    }

    /** Check order (#34): principal present -> expiry in the future -> same principal. */
    @Test
    void expiredCredentialOfAnotherPrincipalOnRefreshIsNakked401NotAPrincipalChange() {
        try (val client = new RawClient(URL)) {
            client.aut("aut000000047", "pia@900");
            assertThat(client.next()).startsWith("ACKaut000000047");
            client.aut("aut000000048", "mallory@-1000");
            assertThat(payload(client.next()).get("status").asInt()).isEqualTo(401);
            client.hello("msg000000048", "still");
            assertThat(client.next()).isEqualTo("ACKmsg000000048\"hello pia\"");
            assertThat(client.awaitClose().getReason()).isEqualTo("authentication expired");
        }
    }

    // --- 9. failed refresh -> NAK 401, session open until the old expiry

    @Test
    void failedRefreshKeepsSessionUntilOldExpiry() {
        try (val client = new RawClient(URL)) {
            val start = System.currentTimeMillis();
            client.aut("aut000000017", "jack@900");
            assertThat(client.next()).startsWith("ACKaut000000017");
            client.aut("aut000000018", "bad-refresh");
            val nak = client.next();
            assertThat(nak).startsWith("NAKaut000000018");
            assertThat(payload(nak).get("status").asInt()).isEqualTo(401);

            // Still usable, also for another refresh attempt.
            client.hello("msg000000018", "retry");
            assertThat(client.next()).isEqualTo("ACKmsg000000018\"hello jack\"");
            client.send("AUTaut000000019[]");
            assertThat(payload(client.next()).get("status").asInt()).isEqualTo(400);
            assertThat(client.isOpen()).isTrue();

            assertThat(client.awaitClose().getReason()).isEqualTo("authentication expired");
            assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(850);
        }
    }

    @Test
    void refreshRejectedOrFailingWithoutPrincipalChangeKeepsSessionUntilOldExpiry() {
        try (val client = new RawClient(URL)) {
            client.aut("aut000000039", "olaf@900");
            assertThat(client.next()).startsWith("ACKaut000000039");
            client.aut("aut000000040", "forbidden");
            assertThat(payload(client.next()).get("status").asInt()).isEqualTo(403);
            client.aut("aut000000041", "crash");
            assertThat(payload(client.next()).get("status").asInt()).isEqualTo(500);
            client.hello("msg000000041", "still");
            assertThat(client.next()).isEqualTo("ACKmsg000000041\"hello olaf\"");
            assertThat(client.awaitClose().getReason()).isEqualTo("authentication expired");
        }
    }

    // --- one AUT at a time

    @Test
    void secondAutWhileFirstIsPendingCloses() {
        try (val client = new RawClient(URL)) {
            client.aut("aut000000020", "slow:kurt");
            client.aut("aut000000021", "kurt");
            val close = client.awaitClose();
            assertPolicyViolation(close);
            assertThat(close.getReason()).isEqualTo("concurrent authentication");
            assertThat(client.nextWithin(500)).isNull();
        }
    }

    @Test
    void secondAutWhileRefreshIsPendingCloses() {
        try (val client = new RawClient(URL)) {
            client.aut("aut000000022", "lena");
            assertThat(client.next()).startsWith("ACKaut000000022");
            client.aut("aut000000023", "slow:lena");
            client.aut("aut000000024", "lena");
            assertThat(client.awaitClose().getReason()).isEqualTo("concurrent authentication");
        }
    }

    // --- connected / disconnected hooks and principal

    @Test
    void connectedFiresOnlyAfterAuthentication() {
        try (val client = new RawClient(URL)) {
            client.aut("aut000000025", "slow:mona");
            sleep(150);
            assertThat(endpoint.connected).doesNotContain("mona", "<anonymous>");
            assertThat(client.next()).startsWith("ACKaut000000025");
            await().until(() -> endpoint.connected.contains("mona"));
        }
        await().until(() -> endpoint.disconnected.contains("mona"));
    }

    @Test
    void hooksFireOnlyForAuthenticatedSessions() {
        // Closed by the client before authenticating, rejected, and timed out.
        try (val client = new RawClient(URL)) {
            assertThat(client.isOpen()).isTrue();
        }
        try (val client = new RawClient(URL)) {
            client.aut("aut000000026", "bad");
            client.awaitClose();
        }
        try (val client = new RawClient(URL)) {
            client.awaitClose();
        }
        sleep(300);
        assertThat(endpoint.connected).doesNotContain("<anonymous>");
        assertThat(endpoint.disconnected).doesNotContain("<anonymous>");
    }

    // --- server-initiated messages never reach an unauthenticated session

    @Test
    void serverPushNeverReachesUnauthenticatedSession() {
        try (val client = new RawClient(URL)) {
            val before = endpoint.seenSessions.size();
            client.aut("aut000000027", "slow:nina");
            await().until(() -> endpoint.seenSessions.size() > before);
            val session = endpoint.seenSessions.get(endpoint.seenSessions.size() - 1);
            assertThat(session.isAuthenticationPending()).isTrue();

            // A broadcast to a pool holding it, and a direct send.
            assertThat(endpoint.seenByAuthenticator.getOpenSessions()).doesNotContain(session);
            endpoint.seenByAuthenticator.broadcast(new Hello("leak")).await();
            val nak = new AtomicReference<ProblemDetail>();
            session.send(new Hello("leak")).peekNak(nak::set);
            assertThat(nak.get()).isNotNull();
            assertThat(nak.get().getTitle()).isEqualTo("Not authenticated");

            assertThat(client.next()).startsWith("ACKaut000000027");
            assertThat(client.nextWithin(300)).isNull();
            assertThat(session.isAuthenticated()).isTrue();
            assertThat(session.getPrincipal().getName()).isEqualTo("nina");
        }
    }

    // --- 10. transition: handshake authenticator

    @Test
    void validHandshakeCredentialAuthenticatesWithoutAut(CapturedOutput output) {
        try (val client = new RawClient(BASE_URL + "/aut-handshake/olga")) {
            client.hello("msg000000028", "hi");
            assertThat(client.next()).isEqualTo("ACKmsg000000028\"hello olga\"");
            await().until(() -> handshakeEndpoint.connected.contains("olga"));
        }
        assertThat(output).contains("authenticated by handshake (");
    }

    @Test
    void invalidHandshakeCredentialCloses() {
        try (val client = new RawClient(BASE_URL + "/aut-handshake/bad-token")) {
            val close = client.awaitClose();
            assertPolicyViolation(close);
            assertThat(close.getReason()).isEqualTo("authentication failed");
            assertThat(handshakeEndpoint.connected).doesNotContain("bad-token");
        }
    }

    @Test
    void noHandshakeCredentialRequiresAut(CapturedOutput output) {
        try (val client = new RawClient(BASE_URL + "/aut-handshake/-")) {
            client.hello("msg000000029", "hi");
            assertThat(client.awaitClose().getReason()).isEqualTo("authentication required");
        }
        try (val client = new RawClient(BASE_URL + "/aut-handshake/-")) {
            client.aut("aut000000030", "paul");
            assertThat(client.next()).isEqualTo("ACKaut000000030");
            client.hello("msg000000030", "hi");
            assertThat(client.next()).isEqualTo("ACKmsg000000030\"hello paul\"");
            await().until(() -> handshakeEndpoint.connected.contains("paul"));
        }
        assertThat(output).contains("authenticated by AUT (");
    }

    @Test
    void autAfterHandshakeAuthenticationIsReauthentication() {
        try (val client = new RawClient(BASE_URL + "/aut-handshake/quinn")) {
            client.aut("aut000000031", "quinn");
            assertThat(client.next()).isEqualTo("ACKaut000000031");
            client.aut("aut000000032", "bad");
            assertThat(payload(client.next()).get("status").asInt()).isEqualTo(401);
            assertThat(client.isOpen()).isTrue();
            client.aut("aut000000033", "rita");
            assertThat(payload(client.next()).get("status").asInt()).isEqualTo(403);
            assertThat(client.awaitClose().getReason()).isEqualTo("principal changed");
        }
    }

    // --- the credential is never logged

    @Test
    void credentialIsNeverLogged(CapturedOutput output) {
        val secret = "s3cr3t" + UUID.randomUUID().toString().replace("-", "");
        try (val client = new RawClient(URL)) {
            client.aut("aut000000034", "sam");
            client.next();
            client.aut("aut000000035", "bad" + secret);
            client.next();
            client.send("AUTaut000000036{\"token\": " + secret);
            client.next();
        }
        try (val client = new RawClient(URL)) {
            client.send("AUTaut000000037{\"token\":{\"" + secret + "\":1}}");
            client.awaitClose();
        }
        try (val client = new RawClient(URL)) {
            // Long enough to be split into several WebSocket frames, whatever is logged of each must not show it.
            client.send("AUTaut000000038{\"token\":\"bad" + "Q".repeat(20_000) + "\"}");
            client.next();
        }
        assertThat(output).doesNotContain(secret.substring(0, 12)).doesNotContain("QQQQQQ");
    }

    private static JsonNode payload(String frame) {
        return readTree(frame.substring(15));
    }

    @SneakyThrows
    private static JsonNode readTree(String json) {
        return OBJECT_MAPPER.readTree(json);
    }

    private static void assertPolicyViolation(CloseStatus close) {
        assertThat(close.getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    @SneakyThrows
    private static void sleep(long millis) {
        Thread.sleep(millis);
    }
}
