# Changelog

Release changes and migration requirements for Zea TCP Transport, with the newest
release first. See the [repository README](README.md) for the quick start and
[application guides](README.md#documentation) for supported APIs and configuration.

- [0.8.3](#083): bounded socket backpressure, unicast allocation reduction,
  streaming JSON and migration from 0.8.2.
- [0.8.2](#082): component injection, authentication defaults, resource budgets
  and migration from 0.8.1.

## 0.8.3

Version 0.8.3 improves bounded socket backpressure, reduces unicast and JSON
encoding allocations, and preserves ordered Session and Game delivery.

### Changes

- Pause bounded socket drains when a channel becomes unwritable, flush admitted
  batches early, and resume from channel writability events. Bursts and slow
  readers retain FIFO ordering without treating a soft write watermark as a hard
  resource-budget failure.
- Keep one write deadline from admission through completion. Release pending
  payloads and reservations on completion, rejection, cancellation and closure,
  including stopped or rejected event-loop cleanup. Expose write-timeout counts
  through JSON and Prometheus flow-control metrics.
- Optimize explicit single-recipient sends by skipping identity deduplication and
  shared fan-out encoding leases. Preserve Game queue ordering, Session admission,
  overload handling and native payload ownership. Multiple-recipient sends retain
  canonical encoding reuse.
- Stream JSON directly into a bounded native application frame, avoiding a
  whole-payload heap array. Preserve the wire format, UTF-8 bytes, null controls
  and 65535-byte application-frame boundary. Release partial output on serializer
  failure or overflow, retaining the frame-limit exception.
- Add regressions for unicast ownership, mixed send ordering, burst and slow-reader
  backpressure, exact JSON bytes, protocol boundaries and failed serialization.

### Migration from 0.8.2

1. Update the dependency to `com.akakata:zea-tcp:0.8.3`. Public event and sending
   APIs and network frame formats remain compatible. Java 21 remains required.
2. Retain the existing task, payload and encoded-byte limits. Soft channel
   unwritability now pauses admitted writes; hard budget rejection and the
   admitted-write deadline still protect resources. Review these budgets against
   representative traffic rather than increasing them to hide overload.
3. Continue treating published event data as immutable and following the
   documented native-buffer ownership contract. Unicast follows the normal
   Game-to-Session-to-socket retention path; fan-out keeps its shared encoding lease.
4. Keep authentication and required listener endpoint configuration from 0.8.2.
   Validate resource budgets with the application's actual handlers and traffic.

See [configuration](docs/CONFIGURATION.md), [event ownership](docs/EVENT_LOOP.md),
[monitoring](docs/MONITORING.md) and [slow business operations](docs/SLOW_BUSINESS.md)
for the complete application contracts.

### Validation

The release checks include 250 regression cases and strict production and test
JavaDoc. Coverage includes ordering, backpressure, native-buffer ownership,
protocol boundaries and real TCP/WebSocket behavior. GitHub Actions validates
the release artifacts before publication.

Maven coordinates: `com.akakata:zea-tcp:0.8.3`.

## 0.8.2

Version 0.8.2 adds application component injection, secure authentication defaults
and bounded resource management while preserving ordered Session and Game events.

### Changes

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

### Migration from 0.8.1

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

See [configuration](docs/CONFIGURATION.md), [core components and security](docs/CORE_COMPONENTS.md)
and [slow business operations](docs/SLOW_BUSINESS.md) for the complete contracts.

### Validation

The release checks include the regression suite, strict production and test
JavaDoc, and a signed Maven publication fixture using a local publisher server.
GitHub Actions repeats these checks before signing and publishing to Maven Central.

Maven coordinates: `com.akakata:zea-tcp:0.8.2`.
