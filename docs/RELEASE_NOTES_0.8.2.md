# Zea TCP Transport 0.8.2

Version 0.8.2 adds application component injection, secure authentication defaults
and bounded resource management while preserving ordered Session and Game events.

## Changes

- Configure application verifiers, token services, Game implementations and service
  factories through `ServerComponents`, using the existing Dagger assembly and
  classpath properties loader.
- Deny login by default. Support explicit anonymous mode for development and
  authenticated AES-GCM tokens with expiry, random secrets and optional persistent
  keys supplied through environment variables.
- Bound outbound source payloads and encoded writes with per-channel limits and a
  shared context budget. Include HTTP in total connection admission and add HTTP
  connection, body, request deadline and idle limits.
- Add `BlockingTaskExecutor` for bounded slow operations. Queued cancellation
  releases admission credits; running work retains its credits until it exits.
- Clean up partial initialization and startup failures, and expose resource usage
  through HTTP and Prometheus metrics.
- Validate required listener endpoints and reject malformed numeric or boolean
  configuration. Keep HTTP bound to loopback in the bundled configuration.
- Expand English documentation and JavaDoc, standardize multiline control flow and
  add repository instructions in `AGENTS.md`.

## Migration from 0.8.1

1. Supply an application `CredentialsVerifier`. Local demos may explicitly enable
   `security.allow.anonymous=true`; this mode does not authenticate an account.
2. Provide all six listener settings, even for disabled listeners: `tcp.port`,
   `tcp.bind.host`, `http.port`, `http.bind.host`, `web.socket.port` and
   `web.socket.bind.host`. Missing or malformed values now fail initialization.
3. Reauthenticate clients using tokens issued by the old fixed-key implementation.
   For tokens that must survive restarts, set `security.token.key.environment` to
   the name of an environment variable containing a Base64 AES key, or inject an
   application-owned `SecurityService`.
4. Review resource limits for your workload. Keep slow operations off Netty I/O,
   Session and Game executors. Submit detached inputs to the bounded worker pool,
   then post results through the original Session or Game event API and validate
   session identity and operation sequence before applying them.

See [configuration](CONFIGURATION.md), [core components and security](CORE_COMPONENTS.md)
and [slow business operations](SLOW_BUSINESS.md) for the complete contracts.

## Validation

The release checks include the regression suite, strict production and test
JavaDoc, and a signed Maven publication fixture using a local publisher server.
GitHub Actions repeats these checks before signing and publishing to Maven Central.

Maven coordinates: `com.akakata:zea-tcp:0.8.2`.
