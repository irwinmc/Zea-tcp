# Changelog

Release changes and migration requirements for Zea TCP Transport, with the newest
release first. See the [repository README](README.md) for the quick start and
[application guides](README.md#documentation) for supported APIs and configuration.

- [0.8.4](#084): bounded shutdown, response ownership, operational health
  and static-resource boundaries.
- [0.8.3](#083): bounded socket backpressure, unicast allocation reduction,
  streaming JSON and migration from 0.8.2.
- [0.8.2](#082): component injection, authentication defaults, resource budgets
  and migration from 0.8.1.

## 0.8.4

Version 0.8.4 hardens shutdown, HTTP resource ownership, operational monitoring
and static-file boundaries while preserving ordered Session and Game events.

### Changes

- Split context, network and dispatcher shutdown requests from completion observation.
  Avoid monitor-held waits, worker self-waits and reentrant cleanup cycles. Bound
  external waits and continue initiating cleanup after stalled or failing stages.
  Track partial assembly independently and close resources returned after shutdown.
- Preserve active callback payloads and resource credits until actual completion,
  including after a shutdown deadline expires.
- Release HTTP response source buffers on success, write rejection and copy failure.
- Record real socket lifetimes, encoded bytes, decoded application messages,
  successful outbound messages and failure callbacks in the default pipelines.
- Replace uptime and cumulative-error readiness heuristics with lifecycle and named
  resource checks. Return JSON with HTTP 503 when readiness is unavailable; detect
  closed resources and outstanding events older than the configured health deadline.
- Reject static traversal, hidden paths and symbolic links. Open files without
  following the final link, use directory handles where supported, and close acquired
  file handles on failed transfers and conditional responses.
- Add 51 regression cases and English lifecycle, monitoring, configuration and
  JavaDoc contracts. Separate resource ownership, shutdown coordination, health
  policy and static-resource resolution from their HTTP and application presenters.

### Migration from 0.8.3

1. Update the dependency to `com.akakata:zea-tcp:0.8.4`. Existing event and sending
   method names, ordered delivery and network frame formats remain compatible.
   Java 21 remains required and dependency versions are unchanged.
2. External context `close()` waits up to `server.shutdown.timeout.millis`, default
   5000, and throws `ShutdownTimeoutException` if cleanup is unfinished. Observe
   `closeAsync()` for actual completion. Managed workers and lifecycle callbacks
   request closure without waiting; an expired wait never releases active resources.
3. Configure probes for HTTP 503 and JSON `DOWN` responses during unavailable
   readiness. Readiness now requires successful listener startup and named resource
   checks; `/health/live` remains live during draining. Review
   `health.maximum.event.delay.millis`, default 5000, for application event deadlines.
   Custom dispatchers implement `isHealthy(long)` and custom Games report shutdown.
4. Default pipelines now record transport and decoded business traffic. Remove
   duplicate application instrumentation from the shared `ServerMetrics` collector,
   or use an independent collector. Errors count failure callbacks, not unique
   failed messages.
5. Static-file roots and their directory structure must remain application-controlled.
   Symlinks, hidden paths and traversal are rejected, including links pointing inside
   the root. Deploy actual files beneath the configured static directory.

See [lifecycle](docs/LIFECYCLE.md), [monitoring](docs/MONITORING.md),
[configuration](docs/CONFIGURATION.md) and [event ownership](docs/EVENT_LOOP.md)
for the complete contracts and filesystem-specific boundary guarantees.

### Validation

The release checks include 301 regression cases and strict production and test
JavaDoc. New coverage includes shutdown races and reentry, late factory ownership,
observation deadlines, HTTP buffer and file-handle cleanup, health policy, static
path boundaries and real TCP/WebSocket traffic counters. GitHub Actions validates
and signs release artifacts before Maven Central publication.

Maven coordinates: `com.akakata:zea-tcp:0.8.4`.

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
