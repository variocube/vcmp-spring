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
