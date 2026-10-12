# Zea TCP Transport

[![Maven Central](https://img.shields.io/maven-central/v/com.akakata/zea-tcp.svg)](https://central.sonatype.com/artifact/com.akakata/zea-tcp)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://openjdk.org/)

Zea TCP Transport is a Java game-server foundation built on Netty. It provides TCP,
HTTP and WebSocket listeners, player sessions, asynchronous event dispatch, a
credential-keyed session cache and HTTP monitoring endpoints.

This documentation describes **0.8.3**, including ordered unicast, streaming JSON
encoding and bounded socket backpressure. Dependency versions below come from
[pom.xml](pom.xml). See the [0.8.3 release notes](docs/RELEASE_NOTES_0.8.3.md)
for changes and migration requirements.

## Features

- TCP, HTTP and WebSocket listeners sharing Netty NIO event-loop groups.
- Configurable TCP JSON or SBE-style binary envelopes; binary WebSocket events.
- Ordered Netty business executors with stable session-based sharding.
- One application Game with a serial queue for shared events and broadcasts.
- Session-targeted delivery that isolates player handlers on shared shards.
- Explicit unicast, selected-recipient sends and broadcasts with admission reports.
- Business task/byte budgets, bounded batched socket writes and event-driven read recovery.
- Login deadlines, read-idle expiry and shared TCP/WebSocket/HTTP socket admission.
- Shared broadcast encoding and live Session/Game flow metrics with timing percentiles.
- Ordered unicast avoids broadcast-cache setup; JSON output streams into bounded native frames.
- Configurable session admission that preserves existing players when full.
- Coordinated session, game, cache, dispatcher and network shutdown.
- Dagger assembly with application-selected Game, authentication and service providers.
- Fail-closed login defaults, externally provisioned token keys and authenticated token expiry.
- Bounded detached blocking operations with cancellation-safe resource accounting.
- Caffeine session caching.
- Health checks, JSON metrics, Prometheus text output and structured logging.

The default game forwards inbound messages to its ordered Game queue. Applications
explicitly select outbound recipients and supply their game rules,
authentication and persistence. Login defaults to denial until an application
supplies its verifier or explicitly enables anonymous demonstration mode. Tokens
use an instance secret or an externally provisioned key and expire. See
[core components and security](docs/CORE_COMPONENTS.md) for injection and migration. Automatic
reconnection and client connection pooling are not implemented.

## Quick Start

### Add the Library

```xml
<dependency>
    <groupId>com.akakata</groupId>
    <artifactId>zea-tcp</artifactId>
    <version>0.8.3</version>
</dependency>
```

Use JDK 21 or later. This repository builds and tests against Java 21.

### Create an Application Entry Point

`CommandLine` is an abstract lifecycle base class. Provide a concrete class with
`main`; the library itself has no executable JAR entry point.

```java
package com.example;

import com.akakata.CommandLine;
import com.akakata.app.Game;
import com.akakata.context.ServerContext;

public final class MyServer extends CommandLine {
    public static void main(String[] args) {
        new MyServer().run(args);
    }

    @Override
    protected ServerContext createServerContext() throws Exception {
        // zea.config is interpreted by this example, not by the framework.
        String resource = System.getProperty("zea.config", "props/conf.properties");
        return new ServerContext(resource);
    }

    @Override
    protected void afterServerStartup() {
        Game game = getContext().getBean(Game.class);
        System.out.println("Connected sessions: " + game.getSessions().size());
    }
}
```

Place your configuration in the application classpath, for example
`src/main/resources/props/conf.properties`. Configuration names are classpath
resource names. The framework does not automatically merge system properties or
environment variables into the properties file.

### Run the Repository Example

From the repository root on macOS or Linux:

```bash
mvn test-compile dependency:copy-dependencies -DincludeScope=runtime
java \
  -cp "target/test-classes:target/classes:target/dependency/*" \
  com.akakata.TestServer
```

The repository example explicitly enables anonymous demonstration credentials.
Application contexts default to denying login. No additional JVM module-access options are required. On Windows, use `;` as
the classpath separator and adapt the shell's line continuation syntax. Stop the example with Ctrl+C to invoke the shutdown hook.

Alternatively, launch through Maven:

```bash
mvn test-compile exec:java -Dexec.mainClass=com.akakata.TestServer
```

The `exec:java` goal runs inside the Maven JVM. The `exec.mainClass` property
selects the entry point while retaining the load-test helper as the default.

### Run in IntelliJ IDEA

Create an **Application** configuration with main class `com.akakata.TestServer`
and the `zea-tcp` test classpath. Use JDK 21 or later and the repository root as the
working directory. No framework-specific VM options are needed.

### Example Ports and Packaging

| Listener | Bundled Port | Address or Purpose |
| --- | --- | --- |
| TCP | 8090 | Login followed by the selected TCP event protocol |
| HTTP | 8081 | Health, metrics, index and static-file routes |
| WebSocket | 8300 | `ws://localhost:8300/`; binary application frames |

Other executable examples are `com.akakata.HttpOnlyServer` (HTTP port 18081),
`com.akakata.WebSocketOnlyServer` (WebSocket port 18300) and
`com.akakata.AdvancedTestServer`. They live in
[src/test/java/com/akakata](src/test/java/com/akakata).

`mvn package` produces the library JAR `target/zea-tcp-0.8.3.jar`. It does not bundle
runtime dependencies or configure a `Main-Class`. The provided [run.sh](run.sh)
expects an application-owned executable `server.jar` in the working directory;
create that application artifact before using the script.

## Configuration

The bundled [props/conf.properties](src/main/resources/props/conf.properties)
contains:

```properties
server.tcp.enabled=true
server.http.enabled=true
server.websocket.enabled=true

tcp.port=8090
http.port=8081
web.socket.port=8300
tcp.bind.host=0.0.0.0
http.bind.host=127.0.0.1
web.socket.bind.host=0.0.0.0

bossThreadCount=2
workerThreadCount=2

so.backlog=100
so.reuseaddr=true
so.keepalive=true
tcp.nodelay=true

reconnect.delay=300000
protocol.type=JSON

# Authentication and Tokens
security.allow.anonymous=false
security.token.key.environment=
security.token.ttl.seconds=3600

# Session Admission
session.maximum=10000

# Session Event Dispatch
session.dispatcher.shards=2
session.queue.capacity=32768
session.queue.bytes=67108864

# Game Event Dispatch
game.queue.capacity=32768
game.queue.bytes=67108864

# Socket Connections and Timeouts
socket.maximum.connections=20000
socket.login.timeout.millis=30000
socket.read.idle.millis=120000

# Socket Outbound Backpressure
socket.maximum.pending.writes=1024
socket.maximum.pending.bytes=1048576
socket.maximum.pending.payload.bytes=1048576
socket.total.pending.bytes=268435456

# HTTP Limits
http.maximum.connections=256
http.maximum.request.bytes=65536
http.request.timeout.millis=10000
http.read.idle.millis=30000

# Detached Blocking Operations
business.blocking.threads=2
business.blocking.queue.capacity=256
business.blocking.queue.bytes=67108864
business.blocking.shutdown.millis=5000

# Protocol Frame Limits
protocol.maximum.frame.bytes=65535
```

`reconnect.delay` is present in the resource but is currently unused. Queue sampling
can additionally be configured with `metrics.event.interval.seconds`; its fallback
is 30 seconds, and a nonpositive value disables sampling.

All six listener port/address properties are required, including those for
listeners disabled by a switch. There is no inferred property name or default
listener endpoint. Invalid, blank or missing listener configuration fails
initialization. Present numeric or boolean parse errors also fail instead of
using an optional property's default.

Defaults for absent optional properties differ from the bundled resource: absent thread counts use one boss
thread and `Runtime.getRuntime().availableProcessors()` workers; an absent
`protocol.type` selects SBE. `protocol.type` affects TCP only. WebSocket events use
the WebSocket JSON codec, and HTTP routing is independent of this property.

See the [configuration and wire-format guide](docs/CONFIGURATION.md) for every
consumed property, loading rules, limits and login formats.

For applications using `IdGeneratorService.generate()`, the optional JVM property
`-DZeaNode=game-1` supplies an identifier prefix. Its key is declared by
`IdGeneratorService.NODE_NAME`. Default Session and Game identifiers use
`generateFor()` and retain their class-name prefixes; see
[identifier node names](docs/CONFIGURATION.md#identifier-node-names).

## Lifecycle and Components

Startup initializes `ServerContext`, invokes `beforeServerStartup()`, starts
enabled listeners, marks the application running and invokes
`afterServerStartup()`. `run(String[])` currently does not parse its arguments and
waits indefinitely after startup.

On normal JVM shutdown, `beforeServerShutdown()` runs before listener shutdown,
and `afterServerShutdown()` runs after listener shutdown but before context
closure. Hooks should release application-owned resources. Hook failures are logged while
context cleanup continues, including after failed startup. Overriding `awaitTermination()` changes the wait behavior;
returning from it does not itself invoke shutdown.

The default context creates one `Game` for the application. `Game` is the
application's session container and shared event boundary; no room registry, room
selection or room-specific configuration is needed. `DefaultGame` forwards input
to registered Game handlers, and applications can subclass `AbstractGame` to implement
shared business behavior. Register application-wide event handlers through
`Game.getEventDispatcher().addHandler(...)` and submit them with `Game.send(...)`.

`ServerContext.getBean()` exposes `Game`, `SessionService`, `ServerManager`,
`CredentialsVerifier`, `SecurityService`, `LoginService`, `OutboundBudget` and
`BlockingTaskExecutor`. Supply application core providers through
`ServerComponents.builder()` and `ServerContext(resource, components)`; see
[component assembly](docs/CORE_COMPONENTS.md#properties-and-assembly).
It is a fixed resource lookup for the supported types. Configuration
is available through `getConfigManager()`.

Closing an initialized context proceeds through these stages:

1. Stop servers.
2. Stop admission to blocking workers, cancel queued operations and request interruption.
3. Close the game, its connected sessions and its dedicated dispatcher.
4. Close `SessionService`, its cleanup scheduler and all sessions it still owns.
5. Stop dispatcher-metrics sampling.
6. Close the process-wide shared dispatcher.
7. Shut down both shared network groups and wait for termination.

The context logs individual cleanup failures and continues with later stages.
Closing a player session removes its handlers from a shared shard while preserving
that shard for other players. Session replacement closes the previous session and
uses identity checks to prevent an old close callback from removing its successor.

Shared dispatchers and the metrics scheduler have process-wide, terminal
lifecycles. Start a new JVM after complete shutdown. See
[event dispatch, concurrency and ownership](docs/EVENT_LOOP.md) for callback
execution and shutdown details.

## Protocols and Event Handling

TCP packets have a two-byte big-endian frame-length prefix. After login, JSON
packets contain an opcode and a four-byte length-prefixed UTF-8 JSON object; SBE
packets contain an eight-byte little-endian header followed by payload bytes.
The SBE implementation provides an envelope rather than generated schema-specific
message codecs. WebSocket application messages are binary frames containing the
opcode and length-prefixed JSON, without the TCP frame-length prefix.

TCP clients send `SESSION_MESSAGE` (`0x33`) for application input. The default
session handler forwards it to the Game queue as a `PlayerMessageEvent`, preserving
the payload and authenticated sender identity. No automatic echo or broadcast occurs.
Business handlers explicitly create `NETWORK_MESSAGE` (`0x34`) events for replies.
The WebSocket decoder translates incoming `0x34` into `0x33`; TCP JSON and SBE
decoders preserve the received opcode, and the authenticated bridge rejects internal
server-only opcodes including TCP `0x34`. Inbound JSON lengths must exactly match
the remaining object bytes; trailing values are rejected. SBE schema/version must
both equal 1, template IDs must fit one byte and block length must match exactly.

For an explicit reply to the authenticated sender, register a Game handler:

```java
import com.akakata.app.impl.DefaultGame;
import com.akakata.event.Event;
import com.akakata.event.EventHandler;
import com.akakata.event.Events;
import com.akakata.event.impl.PlayerMessageEvent;

public final class ApplicationGameExample extends DefaultGame {
    public ApplicationGameExample() {
        getEventDispatcher().addHandler(new EventHandler() {
            @Override
            public int getEventType() {
                return Events.SESSION_MESSAGE;
            }

            @Override
            public void onEvent(Event event) {
                PlayerMessageEvent message = (PlayerMessageEvent) event;
                sendToPlayer(Events.networkEvent(message.getSource()), message.getPlayerSession())
                        .whenComplete((report, failure) -> {
                            if (failure != null) {
                                message.getPlayerSession().close();
                            }
                        });
            }
        });
    }
}
```

Use `sendToPlayers(event, selectedPlayers)` for a selected set and
`sendBroadcast(event)` when every connected player should receive the update.
The default Dagger graph still supplies `DefaultGame`; a standalone application
can select the example subclass through `ServerComponents.builder().gameFactory(...)`.

Each session is bound to a fixed Netty business executor, so accepted events of
all types run serially in submission order. The application's Game owns one
additional business executor for `send()`, `sendToPlayer()`, `sendToPlayers()` and
`sendBroadcast()`. Explicit sends first run in the Game queue, then enter each
recipient's session queue. Socket writes enter their I/O loop in admission order.
Selected-recipient collections are snapshotted and deduplicated by session identity.
Network I/O uses separate Netty event-loop groups. One Game send lazily encodes
once per wire format; TCP and WebSocket JSON recipients share the application
frame. Each recipient receives an independently indexed read-only duplicate.
Socket writes are consumed in FIFO batches of at most 64, with one flush per batch.

Callbacks run synchronously on their business executor. Keep them short; move
blocking work elsewhere and submit its completion back to the owning queue.
There is no automatic callback timeout. Each executor defaults to 32,768 pending
tasks and 64 MiB of estimated payload storage, including running work. Session
shards and the Game have independently configurable budgets. Saturation rejects
submissions without running callbacks
on the publishing thread. `EventDispatcher.fireEvent()`, `Session.onEvent()`
and `Game.send()` return `true` when accepted and `false` when full or closed;
`PlayerSession.sendToGame()` forwards the Game's admission result. These methods
do not wait for capacity or callback completion. Explicit Game recipient sends
return `CompletableFuture<DeliveryReport>` with accepted, rejected and
skipped recipient counts. These counts acknowledge queue admission, not processing
or client receipt. Game queue rejection or cancellation fails the future. A rejected
recipient is closed so later updates cannot silently follow a missing update.
Session state belongs to its session executor; application-wide state belongs to
the Game executor. Never block either queue waiting for work queued to itself.

Network input pauses when either business count or byte usage reaches 75% of its
budget, or the Channel becomes unwritable. Queue low-water notifications and Channel
writability changes resume reads below 50% of both budgets; there is no per-channel
recovery polling.
A full input queue or a pause lasting five seconds closes the affected connection.
Socket sends are bounded by count and source bytes before I/O scheduling, and by
per-channel plus aggregate encoded admission; exceeding
write limits or writing to an unwritable Channel fails the write and closes it.
There is no unbounded overflow queue or automatic retry.

`session.maximum` controls registered credential slots, including pending logins.
Full capacity rejects new credentials; reconnecting an existing credential reuses
its slot. There is no cache-size eviction or cache TTL for online sessions. Explicit
closure and configured read-idle expiry determine their lifetime. TCP/WebSocket/HTTP
connections, including unauthenticated sockets, share `socket.maximum.connections`.
Authentication must finish within `socket.login.timeout.millis`; read inactivity
expires after `socket.read.idle.millis` (zero disables idle expiry). These guards
survive the TCP login pipeline transition.
See [flow control and ownership](docs/EVENT_LOOP.md) for failure and resource rules.

For native `ByteBuf` or `MessageBuffer` payloads, asynchronous dispatch retains its
own reference and the caller releases its original reference after submission.
Handlers that keep a native payload beyond the callback must acquire and release
an additional reference. See [ownership rules](docs/EVENT_LOOP.md#payload-ownership).

## Monitoring

Enable the HTTP listener to access:

```bash
curl http://localhost:8081/health
curl http://localhost:8081/health/live
curl http://localhost:8081/health/ready
curl http://localhost:8081/metrics
curl http://localhost:8081/metrics/connections
curl http://localhost:8081/metrics/traffic
curl http://localhost:8081/metrics/system
curl http://localhost:8081/metrics/event-dispatcher
curl http://localhost:8081/metrics/flow-control
curl http://localhost:8081/metrics/prometheus
```

`ServerMetrics.record*()` methods provide connection, traffic and error counters.
The current default handlers do not call these methods, so applications must
instrument their own paths to populate the counters. Heap, CPU, uptime and channel
group readings are supplied directly; queue depths are sampled separately.
`/metrics/flow-control` and the combined `/metrics` response additionally expose
live Session/Game task and byte budgets, rejection/cancellation counters, queue-wait
and callback-duration P95/P99 bucket bounds, socket gauges and lifetime counters.
Prometheus includes these flow series. The older `/metrics/event-dispatcher` endpoint
continues to return periodically sampled physical session queues.

See the [endpoint reference](docs/MONITORING.md) for exact fields and health checks,
and the [metrics architecture](docs/METRICS_ARCHITECTURE.md) for integration.

## Dependencies

Direct dependencies declared by the current POM:

| Dependency | Version | Role |
| --- | --- | --- |
| `io.netty:netty-all` | 4.2.19.Final | Network I/O, codecs and business executors |
| `com.google.dagger:dagger` | 2.60.1 | Compile-time injection |
| `javax.inject:javax.inject` | 1 | Injection annotations |
| `tools.jackson.core:jackson-databind` | 3.2.3 | JSON serialization |
| `tools.jackson.dataformat:jackson-dataformat-yaml` | 3.2.3 | YAML dependency; configuration loading uses properties |
| `com.github.ben-manes.caffeine:caffeine` | 3.3.0 | Session cache |
| `org.slf4j:slf4j-api` | 2.0.20 | Logging API |
| `ch.qos.logback:logback-classic` | 1.6.5 | Logging implementation |
| `net.logstash.logback:logstash-logback-encoder` | 9.0 | Structured log encoding |
| `org.junit.jupiter:junit-jupiter` | 5.12.2 | Regression tests; test scope |

Jackson code uses the `tools.jackson.*` packages from Jackson 3. Dagger's annotation
processor is `dagger-compiler` 2.60.1. Netty groups use
`MultiThreadIoEventLoopGroup` with `NioIoHandler.newFactory()`.

## Development and Build

```bash
mvn clean compile
mvn test
mvn clean package
mvn clean install
```

The regression suite covers player-event isolation, shared-shard preservation,
session and cache shutdown, replacement and login races, callback-triggered
closure, serial session and Game ordering, reentrant publication, queue rejection,
native-buffer ownership, recipient snapshots and reports, read pause/recovery,
TCP/WebSocket write order, socket source/encoded/aggregate byte budgets, HTTP
connection/request deadlines, custom core providers, secure token validation,
bounded slow-operation cancellation and concurrent capacity admission.
Examples with `main` methods are executable
helpers rather than JUnit tests.

The POM configures Maven Compiler 3.13.0, Surefire 3.2.5, JavaDoc 3.12.0 and
Exec 3.6.1. Its `release` profile adds Enforcer 3.6.3, Sources 3.3.1, JavaDoc attachment,
GPG 3.2.8 signing and Central Publishing 0.11.0. Normal development builds do not
require signing credentials.

### Publish to Maven Central

[The release workflow](.github/workflows/publish-maven-central.yml) runs when a
GitHub Release is published. It checks out the release tag, checks that its version
matches the POM, runs tests and strict English JavaDoc checks with Java 21, then
signs and automatically publishes the main JAR, sources, JavaDoc and POM.
The job waits for Central to report the deployment as published.

The workflow uses Maven Wrapper 3.3.4 with checksum-pinned Maven 3.9.16 for both
verification and deployment. Use `./mvnw` for local releases as well; the release
profile rejects other Maven versions. Maven 3.10.0 adds staging repository metadata
that Central Publishing 0.11.0 does not remove, causing Portal validation to fail.

Before publication, the workflow tests the real release profile against a local
publisher using a temporary signing key and synthetic credentials. It verifies
bundle contents, signatures, checksums and authentication. The release profile
skips redundant local artifact installation before the publisher's own staging.
Publishing uses the dedicated Maven server ID `central-publisher`.

Configure these repository secrets under **Settings → Secrets and variables →
Actions → New repository secret**:

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Username generated with a Central Portal user token |
| `MAVEN_CENTRAL_PASSWORD` | Password generated with the same Portal token |
| `GPG_PRIVATE_KEY` | Complete ASCII-armored export of the signing private key |
| `GPG_PASSPHRASE` | Passphrase protecting that private key |

The Central account must have publishing access to `com.akakata`, and the signing
public key must be available to Central. Use a release tag such as `v0.8.3` or
`0.8.3` matching the POM at that tag. Already published versions cannot be replaced;
choose a new version for subsequent releases. See the
[publishing guide](docs/PUBLISHING.md) for token setup, key preparation, local
verification and troubleshooting.

### Generate API Documentation

```bash
mvn compile javadoc:javadoc javadoc:test-javadoc
mvn compile javadoc:jar
```

Open `target/reports/apidocs/index.html` for the framework API and
`target/reports/testapidocs/index.html` for examples and regression-test helpers.
The archive is `target/zea-tcp-0.8.3-javadoc.jar`. Compilation generates the Dagger
component types needed by JavaDoc. Documentation uses English, UTF-8 and strict
doclint checks; generated Dagger sources are excluded.

### Local Dispatcher Load Test

After preparing the example classpath above:

```bash
java \
  -cp "target/test-classes:target/classes:target/dependency/*" \
  com.akakata.benchmark.SimpleLoadTest
```

The helper uses four producers, counts accepted and rejected events separately,
and waits for a queue barrier before reporting completed throughput and mean
latency. It is a local diagnostic rather than a capacity guarantee or a tail-latency
benchmark. `mvn test-compile exec:java` defaults to this helper.

### Real TCP and WebSocket Load Tests

```bash
java -cp "target/test-classes:target/classes:target/dependency/*" \
  com.akakata.benchmark.NetworkLoadTest \
  --transport=tcp --clients=16 --messages=1000 --window=16 --payload-bytes=128
java -cp "target/test-classes:target/classes:target/dependency/*" \
  com.akakata.benchmark.NetworkLoadTest \
  --transport=websocket --clients=8 --messages=500 --window=64 \
  --payload-bytes=8192 --slow-read-millis=20
```

The driver starts the production context on an ephemeral port, authenticates real
clients, installs an explicit ordered echo Game handler and verifies reply sequence.
It reports completed/failed requests, throughput and exact observed P50/P95/P99.
Each client authenticates and completes `--warmup-messages=100` before all ready
clients start the timed workload. Set this option to zero after a separate warmup
run. Failures and workload deadline expiry produce a nonzero process exit status.
Receive buffers use the operating-system default; `--receive-buffer-bytes=8192`
explicitly selects a constrained reader for diagnostics.
Use `--business-micros=100` to introduce a controlled callback delay,
`--socket-bytes=16384` to constrain encoded output, and `--timeout-seconds=60`
to bound the whole run. Client read delay is included in measured latency.
Each invocation runs one transport and closes its owned context; use separate JVMs
for repeated or multi-process runs. External-server mode uses `--host=... --port=...`
and requires the same login format and sequence-preserving echo handler.
These synthetic workloads provide evidence for tuning; production capacity still
requires representative game logic and deployment-level multi-process tests.

For server-only resource measurements, start the echo context separately:

```bash
java -cp "target/test-classes:target/classes:target/dependency/*" \
  com.akakata.benchmark.NetworkLoadTest --server-only --transport=tcp
```

It prints `READY` with a loopback ephemeral port. Standard-input commands `snapshot`
and `stop` capture server-only CPU/GC/heap/direct-buffer/flow counters and close the
context. EOF also closes it. Run clients in another JVM with that `--port` and
warm up before taking before/after server snapshots. CPU/GC/allocation differences
describe the complete snapshot interval; client latency/throughput excludes its
per-connection warmup. Live-thread allocation counters exclude terminated threads.
The explicit test resource `props/benchmark.properties` configures one boss,
one I/O worker and one Session shard before context construction. Supply a custom
classpath resource with `--config=...` for thread-budget comparisons. Every required
listener binds loopback explicitly. The helper enables anonymous login only for
this isolated echo workload.

Preserve logs and a report for every verification command:

```bash
python3 scripts/test_report.py --report-dir remote-test-results/my-run \
  --name regression -- ./mvnw --batch-mode --no-transfer-progress test
```

The runner writes an aggregate JSON/text report and individual reports for every
JUnit case, including failures. Remote workload reports must additionally identify
artifact checksums, server/client resource limits, warmup and measurement intervals,
and any safety stop. Local SSH credentials and reports are excluded from Git.

## Documentation

| Guide | Contents |
| --- | --- |
| [Configuration](docs/CONFIGURATION.md) | Properties, loading, protocols and fixed limits |
| [Publishing](docs/PUBLISHING.md) | GitHub Releases, Maven Central credentials, signing and deployment |
| [Event loops and lifecycle](docs/EVENT_LOOP.md) | Threads, routing, concurrency, shutdown and payload ownership |
| [Monitoring](docs/MONITORING.md) | Health responses, JSON fields and Prometheus names |
| [Several JVMs on one host](docs/MULTI_PROCESS_TUNING.md) | CPU, memory and queue budgets for measured multi-process deployments |
| [Scaling out independent processes](docs/SCALE_OUT.md) | External placement, sticky Game ownership, node health and proposed cluster capabilities |
| [Metrics architecture](docs/METRICS_ARCHITECTURE.md) | Collectors, instrumentation and sampling lifecycle |
| [Banner customization](docs/BANNER_CUSTOMIZATION.md) | Classpath banners, overrides and displayed version behavior |

## License and Support

Licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
Issues and pull requests are
welcome at [GitHub](https://github.com/irwinmc/Zea-tcp/issues).

Maintainer: **Kelvin**, Akakata — kyia.x52@gmail.com.

Core application assembly and secure defaults are covered in
[Core Components and Security](docs/CORE_COMPONENTS.md). Follow
[Slow Business Operations](docs/SLOW_BUSINESS.md) when introducing database,
remote-service or other blocking work; keep state updates on the original ordered
Session/Game queues and handle late or out-of-order results explicitly.
