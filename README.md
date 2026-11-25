# VCMP Spring

Implementation of the Variocube Messaging Protocol (VCMP) in Java for the Spring framework.
Both client and server are available in this library.
 
## Versions

- The `3.x` versions target Spring Boot 3 and are in the `master` branch.
- The `2.x` versions target Spring Boot 2 and are in the `v2` branch.

New features and fixes should be merged into both branches. Use GitHub releases to create the
corresponding releases.

In order to test locally, you can publish a version to your local maven repository:

```shell
./gradlew -Pversion=0.0.0 publishToMavenLocal
```

## Protocol summary

A VCMP frame is a WebSocket text message starting with a three-letter type:

| Frame | Layout | Meaning |
|-------|--------|---------|
| `MSG` | `MSG` + 12-char id + JSON payload | A message with an `@type`, dispatched to the matching `@VcmpListener`. |
| `ACK` | `ACK` + id + optional JSON payload | The message (or `AUT`) with that id succeeded; the payload is the listener's result. |
| `NAK` | `NAK` + id + optional problem detail | The message (or `AUT`) with that id failed. |
| `HBT` | `HBT` + interval in ms | Heartbeat, see [Callback semantics](#callback-semantics). |
| `AUT` | `AUT` + 12-char id + JSON object | Client → server: authenticate this session (see [Session authentication](#session-authentication)). Never dispatched to listeners, never has an `@type`, and its payload is never logged. |

## Session authentication

Browsers can't set headers on a WebSocket handshake, so a credential would have to go into the URL, and from there into
access logs, proxies and CDNs. With session authentication (`AUT`, variocube/vcmp-spring#34) the client sends it as the
first frame instead. vcmp-js and vcmp-rs implement the same protocol; the contract suite in vcmp-rs pins it.

The client sends `AUT` + id + a JSON object of the endpoint's shape (e.g. `{"token": "…"}`). The server answers:

- `ACK` + id, optionally with a JSON object: its member `expiresIn` (integer, milliseconds from the `ACK`) says when
  the authentication expires (absent: it doesn't); other members are the endpoint's, passed to the client.
- `NAK` + id with status 400 (not a JSON object, or the endpoint does not use authentication), 401 (invalid
  credential), 403 (valid, but not allowed; also: another principal on re-authentication) or 500 (the authenticator
  failed unexpectedly).

Servers close sessions with code 1008 (policy violation) for every rule below; the reason is informative only. A
client closing a session for authentication reasons uses 4008 with the same reasons (browsers don't let a client close
with 1008); peers treat 4008 like 1008.

### Securing an endpoint

```java
@VcmpEndpoint(path = "/api/booking/vcmp", authenticated = true)
public class BookingEndpoint {

    record BookingCredential(String bookingId, String token) {}

    @VcmpAuthenticator
    public VcmpAuthentication authenticate(BookingCredential credential) {
        var booking = bookings.findByAccessToken(credential.bookingId(), credential.token());
        if (booking == null) {
            return null; // NAK 401, session closed
        }
        return VcmpAuthentication.of(new BookingPrincipal(booking.getId()))     // a Principal with value equality
                .withDetails(Map.of("booking", booking.getId()));               // optional ACK members
        // or VcmpAuthentication.of(principal, jwt.getExpiresAt()) for a credential that expires
    }

    @VcmpSessionConnected   // fires only after the session authenticated
    public void connected(VcmpSession session) {
        pool.add(session);  // session.getPrincipal() is the BookingPrincipal
    }
}
```

- **The opt-in is explicit.** `authenticated = true` requires a `@VcmpAuthenticator` method; an authenticator on an
  endpoint without it fails at startup. Endpoints without the opt-in behave exactly as before; an `AUT` sent to them is
  answered with `NAK` 400 and the session stays open.
- **The authenticator** takes the credential (the payload converted to its parameter type: a record, a `Map` or a
  `JsonNode`) and optionally the `VcmpSession`. It returns a `VcmpAuthentication` (principal, optional expiry,
  optional ACK details) or `null` (401). A thrown exception maps like a listener's (`ResponseStatusException(FORBIDDEN)`
  → 403); an unexpected one is a 500. It runs on a worker thread, under the connect throttle for the first `AUT`.
- **Before authentication** only `HBT` and `AUT` are accepted. A `MSG`, `ACK` or `NAK` (or an unparseable frame or
  a binary WebSocket message), a second `AUT` while one is
  pending, a `NAK` to the first `AUT`, or no successful `AUT` within `authTimeoutMillis` (default 10 s) closes the
  session.
- **Hooks:** `@VcmpSessionConnected` fires only after authentication succeeded (after the `ACK` is sent), so an
  application can't register or push to an unauthenticated session; `@VcmpSessionDisconnected` fires only for sessions
  that were connected in that sense. `VcmpSession.send` on an unauthenticated session fails (`503 Not authenticated`)
  and `VcmpSessionPool` leaves such sessions out of broadcasts and sends.
- **Principal:** `VcmpSession.getPrincipal()` returns the authenticator's principal (so `getUsername()`,
  `VcmpSessionPool.send(recipient, …)` and `String` listener parameters work as with Spring Security);
  `isAuthenticated()`, `getAuthenticationExpiresAt()` and `getAuthenticationDetails()` tell the rest.
- **Expiry and re-authentication:** with an expiry, the session is closed when it is reached, unless the client
  re-authenticated in time. An `AUT` on an authenticated session is a re-authentication (messages keep flowing): the
  same principal (by `equals`) → `ACK` with the new `expiresIn`, the expiry is replaced (or removed); another principal
  → `NAK` 403 and the session is closed; invalid → `NAK` 401 (any other rejection by the authenticator: its status)
  and the session stays open until its current expiry, so the client can retry. An authenticator that fails
  unexpectedly (an exception without a status) is a `NAK` 500, failing closed the same way: the first `AUT` closes
  the session, a refresh keeps it until its current expiry. The same holds for anything that fails after the
  authenticator accepted the credential (an `ACK` payload that can't be serialized, a principal whose `equals` throws):
  a session is authenticated only once its `ACK` is out. A `VcmpAuthentication` without a principal can't be created
  (`NullPointerException` in the authenticator, so `NAK` 500). An endpoint with its own notion of "same principal" compares in the
  authenticator and returns the session's current principal.
- The credential is never logged by VCMP (frame logging shows `AUT#<id>:<redacted>`, WebSocket chunks only their
  length). `wss://` is still required for anything secret.

### Transition: old clients with a credential in the handshake

An endpoint switching to `AUT` can keep accepting old clients that send their credential in the handshake (path
variable, query parameter, header) with a `@VcmpHandshakeAuthenticator`, called once when the session is established:

```java
@VcmpHandshakeAuthenticator
public VcmpAuthentication authenticateHandshake(VcmpHandshake handshake) {
    var token = handshake.pathVariables().get("token");   // also: uri(), headers(), principal()
    if (token == null) {
        return null;                                     // no handshake credential: wait for AUT
    }
    var account = accounts.findByToken(token);
    if (account == null) {
        throw new IllegalArgumentException("invalid");  // invalid: closed (1008), no fallback to AUT
    }
    return VcmpAuthentication.of(new AccountPrincipal(account.getId()));  // authenticated at connect, no AUT
}
```

An `AUT` on a session authenticated by its handshake is a re-authentication (same principal required). Return no expiry
from the handshake authenticator unless there is a reason: an old client can't re-authenticate. Every authentication is
logged with running totals (`Session … authenticated by handshake (3 by handshake, 41 by AUT since start)`), so you can
tell when the last old client is gone and remove the handshake authenticator.

### Client

```java
@VcmpClient(url = "${center.url}/vcmp")
public class CenterClient extends BasicVcmpClient {

    @VcmpCredential   // called on every (re)connect and before the expiry; may return a CompletionStage
    public Map<String, String> credential() {
        return Map.of("bearer", tokenService.fetchAccessToken());
    }

    @VcmpAuthenticationFailed   // optional
    public void authenticationFailed(ProblemDetail problem) {
        log.warn("VCMP authentication failed: {}", problem);
    }
}
```

- With a `@VcmpCredential` method the client sends `AUT` first on every session; `@VcmpSessionConnected` (and so
  `BasicVcmpClient.isConnected()`) fires only after the `ACK`, and nothing else can be sent before it.
- A `NAK`, no answer within `authTimeoutMillis` (default 10 s; e.g. an older server that doesn't know `AUT`), or a
  failing credential method is an authentication error: `@VcmpAuthenticationFailed` is called (also
  `VcmpConnectionManager.getConnectionError()`, cleared on the next successful authentication), the client closes the
  session (4008) and reconnects under the normal rules, with a fresh credential. Close reasons: `authentication failed`
  for a `NAK` to the first `AUT` or a failing credential method, `authentication timeout` for no answer.
- The answer timeout starts when the credential is requested: a credential method whose `CompletionStage` never
  completes ends like an unanswered `AUT`. `authTimeoutMillis` and `authRefreshRetryMillis` below 1 s are rejected at
  startup (not clamped), as is a negative lead time.
- An `ACK` with a payload that is no JSON object counts as no payload, and an `expiresIn` that is no non-negative
  integer as absent (a warning is logged): the session is authenticated, without a refresh; the server closes it when
  it is due.
- When the `ACK` carries `expiresIn`, the client re-authenticates `authRefreshLeadMillis` before it (default:
  `min(60 s, 20 % of expiresIn)`; at most half of `expiresIn`, and never sooner than 1 s after the `ACK`), counted from
  receipt so clock skew doesn't matter. A refresh answered with `NAK` is reported like any authentication error and retried every
  `authRefreshRetryMillis` (default 5 s) until the expiry; after the expiry the server closes the session and the
  client reconnects as usual. After a principal change the server closes the session right away, so there is nothing
  to retry. A refresh without an answer within the answer timeout is reported too, but not retried on the session (a
  second `AUT` while the first is pending would make the server close it): the client closes the session (4008) and
  reconnects with a fresh credential.
- `VcmpConnectionManager` has the same options as setters (`setAuthTimeout`, `setAuthRefreshLeadTime`,
  `setAuthRefreshRetryInterval`).

## Type aliases

A listener normally handles the one `@type` id of its message class (`@JsonTypeName`, else the unqualified class name).
When a peer sends a different id for the same message, name it as an alias instead of duplicating the class
(center#537: controller-rs sends `cube:CubeUtilization`, the Java controller `CubeUtilization`):

```java
@VcmpListener(aliases = "cube:CubeUtilization")
public void handleCubeUtilization(CubeUtilization utilization) { ... }
```

Incoming frames with the class's own id or any alias deserialize to the same class and reach the same listener.
**Outgoing frames always use the class's own id.** An alias that is blank, equal to the listener's own id,
repeated within one listener, or already the type id or alias of another listener of the same endpoint fails the
endpoint's construction.

## Callback semantics

`VcmpSession.send(...)` returns a `VcmpCallback` that is completed by the peer's ACK or NAK. It is
additionally failed with a NAK when the message can never be acknowledged:

| Condition | Status | Title |
|-----------|--------|-------|
| The peer's listener failed | the peer's own status | the peer's own title |
| Sending on an already closed session | `503` | `Session closed` |
| The session closed while the ACK was still outstanding | `503` | `Session closed` |
| Sending without a session (`BasicVcmpClient.send` while disconnected, `VcmpSessionPool.send` to a recipient with no connected session) | `503` | `Not connected` |
| Transient failure while sending on an open session (e.g. the send-lock timeout under contention) | `500` | `Send failed` |
| The outbound message could not be serialized (deterministic — retrying cannot help) | `500` | `Message handling failed` |

"The peer's own status" is the status the listener chose: an `ErrorResponseException` (such as
`ResponseStatusException`) is forwarded as its `ProblemDetail`; an exception whose class is annotated
`@ResponseStatus` maps to that code, with the annotation's reason or the exception's message as
detail. This holds for a listener returning a `CompletableFuture` too: the future's failure is
unwrapped before it is mapped. Any other exception NAKs as `500` / `Message handling failed` with the
exception's message as detail. Deliberate statuses are preserved, including `500`, so the status
alone does not distinguish a listener crash from a deliberate rejection.

A callback settles **at most once**: a combined callback (`VcmpCallback.all`/`any`) whose members
fail one after another — e.g. a broadcast during a rolling restart — delivers only the first
settlement to its handlers.

Callbacks are thread-safe. ACKs and NAKs are dispatched on the worker pool, so the members of an
`all`/`any` may settle concurrently, and a callback may settle while its handler is being attached. Every
member's ACK counts, and each handler fires exactly once.

All `503` cases mirror what the JavaScript implementation reports for the same conditions
(variocube/vcmp-js#32), so a `503` can be treated uniformly as a retryable transport condition.
Note that a `503` does not imply the peer never processed the message — an ACK lost to a connection
drop still means the listener ran. Retry only what is idempotent.

### Local connection problems vs peer NAKs

Every ProblemDetail representing a **connection-level problem detected locally** by this VCMP
instance — the `503` rows and the `500 Send failed` row above — carries the property
`LocalConnectionProblem.PROPERTY` (`"vcmp-local-connection"`). Deliberately **not** marked: the
serialization failure of an outbound message (deterministic — retrying cannot help) and the generic
no-arg `VcmpCallback.failed()` (a handler-level failure, not a transport condition).

NAK-delivered ProblemDetails from a peer never carry the marker: it is stripped when a NAK frame is
serialized and removed from parsed NAK payloads and top-level `ProblemDetail` ACK results, so a peer
can neither observe nor forge it on those channels. (ProblemDetails nested inside other message or
result types are not deep-scrubbed — do not apply the marker check to those.)

Consumers that need to distinguish "this message may never have reached the peer and retrying later
may succeed" from "this message failed for good" should use
`LocalConnectionProblem.isTransportFailure(errorResponseException)` — it also covers the timeout and
interrupt cases, which surface as a locally thrown `ResponseStatusException` from
`VcmpCallback.await(...)` rather than a marked ProblemDetail. Note that a transport failure does not
guarantee the peer never processed the message — an ACK lost to a connection drop still means the
listener ran; retry only what is idempotent (see above). Never match on status codes: a peer's NAK
may carry a `503` or `408` of its own.

**VCMP does not impose a timeout on acknowledgement.** As long as the session stays open, a
callback waits for a peer that is slow — a listener may legitimately take minutes. Bounding that
wait is the caller's decision: use `VcmpCallback.await()` (20 s default), `awaitSeconds(int)`, or
`await(long, TimeUnit)`. A connection that has actually died is detected by the heartbeat and
closed, which fails the callback via the `Session closed` path above — **provided a heartbeat is
running**: the library does not start one by itself. One side must call
`VcmpSession.initiateHeartbeat(...)` (typically in the `@VcmpSessionConnected` handler, as all
Variocube backends do); both sides then watch it. Without an initiated heartbeat, a half-open
connection goes undetected and pending callbacks are not settled.

## Threading

VCMP schedules its work on a shared pool of **daemon** threads (`VCMP-Worker-*`, `VCMP-Scheduler-*`).
They never keep a JVM alive: if application startup fails, the process can exit and a container
restart policy can recover (see variocube/center#427).

## Server startup robustness

Servers embedding VCMP endpoints get two protections against fleet-wide reconnect storms racing
application startup (the web server port opens before startup has finished):

| Property | Default | Meaning |
|----------|---------|---------|
| `vcmp.server.ready-gate.enabled` | `true` | Reject websocket handshakes with `503` + `Retry-After` until `ApplicationReadyEvent`. The filter runs at highest precedence, before Spring Security. Clients reconnect with retry. |
| `vcmp.server.connect-concurrency` | `8` | Bound on concurrently running `@VcmpSessionConnected` handlers (on authenticated endpoints together with the authenticator of the first `AUT`), shared across all endpoints of the application. Queues connect storms instead of exhausting resources (e.g. the DB pool). `<= 0` disables throttling. |

## Refreshing client authentication (handshake headers)

For endpoints that authenticate the handshake (e.g. with Spring Security). For session authentication, see
[Client](#client).

`@VcmpHttpHeaders` runs immediately before **every** handshake, including automatic reconnects,
with a fresh `HttpHeaders` instance. Mint short-lived Bearer tokens inside this hook rather than
at bean construction. Do not retain the supplied headers or mutate them asynchronously.

If the hook fails, VCMP skips the handshake and follows the normal reconnect policy. It never
reuses headers from a previous attempt or sends an anonymous handshake after a refresh failure.
Existing static-header hooks remain compatible, but now run once per attempt instead of once
when the connection manager is constructed. Keep hook work brief.
