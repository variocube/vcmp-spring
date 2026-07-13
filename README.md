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
