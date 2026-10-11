# Zea TCP Transport

[![Maven Central](https://img.shields.io/maven-central/v/com.akakata/zea-tcp.svg)](https://central.sonatype.com/artifact/com.akakata/zea-tcp)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://openjdk.org/)

Zea TCP Transport is a Java game-server foundation built on Netty. It provides TCP,
HTTP and WebSocket listeners, player sessions, asynchronous event dispatch, a
credential-keyed session cache and HTTP monitoring endpoints.

This documentation describes version **0.8.0** and the implementation in this
checkout. Dependency versions below come from [pom.xml](pom.xml).

## Features

- TCP, HTTP and WebSocket listeners sharing Netty NIO event-loop groups.
- Configurable TCP JSON or SBE-style binary envelopes; binary WebSocket events.
- Ordered Netty business executors with stable session-based sharding.
- One application Game with a serial queue for shared events and broadcasts.
- Session-targeted delivery that isolates player handlers on shared shards.
- Explicit unicast, selected-recipient sends and broadcasts with admission reports.
- Bounded business queues, socket write budgets and automatic read-pressure control.
- Configurable session admission that preserves existing players when full.
- Coordinated session, game, cache, dispatcher and network shutdown.
- Dagger compile-time dependency injection and Caffeine session caching.
- Health checks, JSON metrics, Prometheus text output and structured logging.

The default game forwards inbound messages to its ordered Game queue. Applications
explicitly select outbound recipients and supply their game rules,
authentication and persistence. The bundled credentials verifier accepts incoming
login payloads without validating an account, and the default token cipher uses a
built-in key. Replace these components for application authentication. Automatic
reconnection and client connection pooling are not implemented.

## Quick Start

### Add the Library

```xml
<dependency>
    <groupId>com.akakata</groupId>
    <artifactId>zea-tcp</artifactId>
    <version>0.8.0</version>
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

No additional JVM module-access options are required. On Windows, use `;` as
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

`mvn package` produces the library JAR `target/zea-tcp-0.8.0.jar`. It does not bundle
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

bossThreadCount=2
workerThreadCount=2

so.backlog=100
so.reuseaddr=true
so.keepalive=true
tcp.nodelay=true

reconnect.delay=300000
protocol.type=JSON

session.maximum=10000
game.queue.capacity=32768
socket.maximum.pending.writes=1024
socket.maximum.pending.bytes=1048576
```

`reconnect.delay` is present in the resource but is currently unused. Queue sampling
can additionally be configured with `metrics.event.interval.seconds`; its fallback
is 30 seconds, and a nonpositive value disables sampling.

Fallbacks differ from the bundled resource: absent thread counts use one boss
thread and `Runtime.getRuntime().availableProcessors()` workers; an absent
`protocol.type` selects SBE. `protocol.type` affects TCP only. WebSocket events use
the WebSocket JSON codec, and HTTP routing is independent of this property.

See the [configuration and wire-format guide](docs/CONFIGURATION.md) for every
consumed property, loading rules, limits and login formats.

## Lifecycle and Components

Startup initializes `ServerContext`, invokes `beforeServerStartup()`, starts
enabled listeners, marks the application running and invokes
`afterServerStartup()`. `run(String[])` currently does not parse its arguments and
waits indefinitely after startup.

On normal JVM shutdown, `beforeServerShutdown()` runs before listener shutdown,
and `afterServerShutdown()` runs after listener shutdown but before context
closure. Hooks should release application-owned resources and handle their own
cleanup failures. Overriding `awaitTermination()` changes the wait behavior;
returning from it does not itself invoke shutdown.

The default context creates one `Game` for the application. `Game` is the
application's session container and shared event boundary; no room registry, room
selection or room-specific configuration is needed. `DefaultGame` forwards input
to registered Game handlers, and applications can subclass `AbstractGame` to implement
shared business behavior. Register application-wide event handlers through
`Game.getEventDispatcher().addHandler(...)` and submit them with `Game.send(...)`.

`ServerContext.getBean()` exposes `Game`, `SessionService` and `ServerManager`.
It is not a general-purpose lookup for arbitrary Dagger bindings. Configuration
is available through `getConfigManager()`.

Closing an initialized context proceeds through these stages:

1. Stop servers.
2. Close the game, its connected sessions and its dedicated dispatcher.
3. Close `SessionService`, its cleanup scheduler and all sessions it still owns.
4. Stop dispatcher-metrics sampling.
5. Close the process-wide shared dispatcher.
6. Shut down both shared network groups and wait for termination.

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
decoders preserve the received opcode.

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
can instantiate the example subclass, or replace the Game provider in its own graph.

Each session is bound to a fixed Netty business executor, so accepted events of
all types run serially in submission order. The application's Game owns one
additional business executor for `send()`, `sendToPlayer()`, `sendToPlayers()` and
`sendBroadcast()`. Explicit sends first run in the Game queue, then enter each
recipient's session queue. Socket writes enter their I/O loop in admission order.
Selected-recipient collections are snapshotted and deduplicated by session identity.
Network I/O uses separate Netty event-loop groups.

Callbacks run synchronously on their business executor. Keep them short; move
blocking work elsewhere and submit its completion back to the owning queue.
There is no automatic callback timeout. Queues are bounded to 32,768 pending tasks
per executor by default. Saturation rejects submissions without running callbacks
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

Network input pauses when a business queue reaches 75% capacity or the Channel
becomes unwritable. Reads resume below 50% capacity when the Channel is writable.
A full input queue or a pause lasting five seconds closes the affected connection.
Socket sends are bounded before I/O scheduling and again by encoded bytes; exceeding
write limits or writing to an unwritable Channel fails the write and closes it.
There is no unbounded overflow queue or automatic retry.

`session.maximum` controls registered credential slots, including pending logins.
Full capacity rejects new credentials; reconnecting an existing credential reuses
its slot. There is no cache-size eviction or cache TTL for online sessions. Explicit
closure and application-configured transport idle handling determine their lifetime.
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
curl http://localhost:8081/metrics/prometheus
```

`ServerMetrics.record*()` methods provide connection, traffic and error counters.
The current default handlers do not call these methods, so applications must
instrument their own paths to populate the counters. Heap, CPU, uptime and channel
group readings are supplied directly; queue depths are sampled separately.
`/metrics` excludes dispatcher queues, and Prometheus output currently excludes
them too.

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
TCP/WebSocket write order, socket byte budgets and concurrent capacity admission.
Examples with `main` methods are executable
helpers rather than JUnit tests.

The POM configures Maven Compiler 3.13.0, Surefire 3.2.5, JavaDoc 3.12.0,
Exec 3.6.1 and Central Publishing 0.7.0. Its `release` profile adds Sources 3.3.1,
JavaDoc attachment and GPG 3.2.8 signing. Normal development builds do not require
signing credentials.

### Generate API Documentation

```bash
mvn compile javadoc:javadoc javadoc:test-javadoc
mvn compile javadoc:jar
```

Open `target/reports/apidocs/index.html` for the framework API and
`target/reports/testapidocs/index.html` for examples and regression-test helpers.
The archive is `target/zea-tcp-0.8.0-javadoc.jar`. Compilation generates the Dagger
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

## Documentation

| Guide | Contents |
| --- | --- |
| [Configuration](docs/CONFIGURATION.md) | Properties, loading, protocols and fixed limits |
| [Event loops and lifecycle](docs/EVENT_LOOP.md) | Threads, routing, concurrency, shutdown and payload ownership |
| [Monitoring](docs/MONITORING.md) | Health responses, JSON fields and Prometheus names |
| [Metrics architecture](docs/METRICS_ARCHITECTURE.md) | Collectors, instrumentation and sampling lifecycle |
| [Banner customization](docs/BANNER_CUSTOMIZATION.md) | Classpath banners, overrides and displayed version behavior |

## License and Support

Licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
Issues and pull requests are
welcome at [GitHub](https://github.com/akakata/zea-tcp/issues).

Maintainer: **Kelvin**, Akakata — kyia.x52@gmail.com.
