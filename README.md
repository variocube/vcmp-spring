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

## Callback semantics

`VcmpSession.send(...)` returns a `VcmpCallback` that is completed by the peer's ACK or NAK. It is
additionally failed with a NAK when the message can never be acknowledged:

| Condition | Status | Title |
|-----------|--------|-------|
| The peer's listener failed | the peer's own status | the peer's own title |
| Sending on an already closed session | `503` | `Session closed` |
| The session closed while the ACK was still outstanding | `503` | `Session closed` |
| Sending without a session (`BasicVcmpClient.send` while disconnected, `VcmpSessionPool.send` to a recipient with no connected session) | `503` | `Not connected` |

A callback settles **at most once**: a combined callback (`VcmpCallback.all`/`any`) whose members
fail one after another — e.g. a broadcast during a rolling restart — delivers only the first
settlement to its handlers.

All `503` cases mirror what the JavaScript implementation reports for the same conditions
(variocube/vcmp-js#32), so a `503` can be treated uniformly as a retryable transport condition.
Note that a `503` does not imply the peer never processed the message — an ACK lost to a connection
drop still means the listener ran. Retry only what is idempotent.

### Local connection problems vs peer NAKs

Every ProblemDetail representing a **connection-level problem detected locally** by this VCMP
instance — the `503` rows above, plus transient failures while sending on an open session (e.g. the
send-lock timeout) — carries the property `LocalConnectionProblem.PROPERTY`
(`"vcmp-local-connection"`). Deliberately **not** marked: a serialization failure of the outbound
message (deterministic — retrying cannot help) and the generic no-arg `VcmpCallback.failed()` (a
handler-level failure, not a transport condition).

NAK-delivered ProblemDetails from a peer never carry the marker: it is stripped when a NAK frame is
serialized and removed from parsed NAK payloads and top-level `ProblemDetail` ACK results, so a peer
can neither observe nor forge it on those channels. (ProblemDetails nested inside other message or
result types are not deep-scrubbed — do not apply the marker check to those.)

Consumers that need to distinguish "the peer never saw this message and retrying later may succeed"
from "this message failed for good" should use
`LocalConnectionProblem.isTransportFailure(errorResponseException)` — it also covers the timeout and
interrupt cases, which surface as a locally thrown `ResponseStatusException` from
`VcmpCallback.await(...)` rather than a marked ProblemDetail. Never match on status codes: a peer's
NAK may carry a `503` or `408` of its own.

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
| `vcmp.server.connect-concurrency` | `8` | Bound on concurrently running `@VcmpSessionConnected` handlers, shared across all endpoints of the application. Queues connect storms instead of exhausting resources (e.g. the DB pool). `<= 0` disables throttling. |
