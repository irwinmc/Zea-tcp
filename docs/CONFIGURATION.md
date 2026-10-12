# Configuration Guide

This guide describes the configuration consumed by Zea TCP Transport 0.8.0.
Dependency versions and build commands are listed in the [README](../README.md).

## Loading Configuration

`ServerContext()` loads the classpath resource `props/conf.properties`, supplied by
[src/main/resources/props/conf.properties](../src/main/resources/props/conf.properties).
`ServerContext(String)` selects another classpath resource name. These constructors
do not read an arbitrary filesystem path or URL.

`ConfigurationManager` uses `Properties.load(InputStream)` and closes the stream.
A missing resource raises `IOException`. Property names are case-sensitive. The
loader does not read YAML, merge environment variables, apply general `-D` overrides
or reload a file after startup.

| Getter | Missing Value | Present Value |
| --- | --- | --- |
| `getInt(key, defaultValue)` | Supplied default | Trimmed integer; malformed input logs a warning and falls back |
| `getBoolean(key, defaultValue)` | Supplied default | Trimmed `Boolean.parseBoolean`; only case-insensitive `true` is true |
| `getString(key, defaultValue)` | Supplied default | Original property string, without trimming |
| `getProperties()` | Not applicable | Live mutable `Properties` object |

## Supported Properties

The **fallback** is used when a key is absent, or when integer parsing fails.
The **bundled value** is the value in this checkout's default resource.

| Property | Fallback | Bundled Value | Effect |
| --- | --- | --- | --- |
| `server.tcp.enabled` | `true` | `true` | Start the TCP listener through `startServers()` |
| `server.http.enabled` | `true` | `true` | Start the HTTP listener through `startServers()` |
| `server.websocket.enabled` | `true` | `true` | Start the WebSocket listener through `startServers()` |
| `tcp.port` | `8090` | `8090` | TCP bind port |
| `http.port` | `8081` | `8081` | HTTP bind port |
| `web.socket.port` | `8300` | `8300` | WebSocket bind port |
| `bossThreadCount` | `1` | `2` | Shared accepting-group thread count |
| `workerThreadCount` | Available processors | `2` | Shared network-I/O group thread count |
| `so.backlog` | `100` | `100` | Listening channel's `SO_BACKLOG` |
| `so.reuseaddr` | `true` | `true` | Listening channel's `SO_REUSEADDR` |
| `so.keepalive` | `true` | `true` | Accepted channel's `SO_KEEPALIVE` |
| `tcp.nodelay` | `true` | `true` | Accepted channel's `TCP_NODELAY` |
| `protocol.type` | `SBE` | `JSON` | TCP login decoder and post-login protocol |
| `metrics.event.interval.seconds` | `30` | Absent | Shared-dispatcher queue sampling period in seconds |
| `session.maximum` | `10000` | `10000` | Positive credential-slot admission limit; full capacity rejects new logins |
| `session.dispatcher.shards` | `max(2, highestOneBit(availableProcessors))` | `2` | Shared session executors, positive and rounded up to a power of two |
| `session.queue.capacity` | `32768` | `32768` | Pending tasks per fixed session executor, at least 16 |
| `session.queue.bytes` | `67108864` | `67108864` | Estimated retained payload bytes per session executor, including running work |
| `game.queue.capacity` | `32768` | `32768` | Maximum pending Game tasks; must be at least 16 |
| `game.queue.bytes` | `67108864` | `67108864` | Estimated admitted Game payload bytes, including running work |
| `socket.maximum.connections` | `20000` | `20000` | Shared TCP/WebSocket socket slots including unauthenticated clients |
| `socket.login.timeout.millis` | `30000` | `30000` | Positive connection-to-authentication deadline, including WebSocket upgrade |
| `socket.read.idle.millis` | `120000` | `120000` | Read inactivity expiry; zero disables it |
| `socket.maximum.pending.writes` | `1024` | `1024` | Positive maximum admitted socket writes awaiting completion |
| `socket.maximum.pending.bytes` | `1048576` | `1048576` | Positive maximum encoded bytes awaiting socket completion |
| `protocol.maximum.frame.bytes` | `65535` | `65535` | Inbound application frame bytes, between 8 and 65535; excludes transport headers |

These values are read by
[ServerContext](../src/main/java/com/akakata/context/ServerContext.java),
[ServerModule](../src/main/java/com/akakata/context/module/ServerModule.java),
[ProtocolModule](../src/main/java/com/akakata/context/module/ProtocolModule.java),
[ServiceModule](../src/main/java/com/akakata/context/module/ServiceModule.java) and
[ServerManagerImpl](../src/main/java/com/akakata/server/impl/ServerManagerImpl.java).

### Server Selection and Binding

The parameterless `ServerManager.startServers()` checks the three enable flags.
Disabling a listener does not prevent Dagger from constructing its server object
or the context from creating game, session, dispatcher and network resources.
All flags may be false when using the context without network listeners.

The overload `startServers(flashPort, tcpPort, httpPort, webSocketPort)` starts
listeners for positive supplied ports and does not consult the enable flags.
`flashPort` is unused. Bind addresses are wildcard socket addresses; there is no
bind-host property in the default module. Port zero delegates ephemeral-port
selection to Netty in the parameterless startup path.

### Network Threads and Socket Options

TCP, HTTP and WebSocket share one boss group and one worker group owned by
`NetworkBootstrap`. Counts apply once to those groups, not once per listener.
A count of zero requests Netty's default sizing. Negative counts are unsupported
by `NetworkBootstrap`; the properties loader does not validate their range.

The groups use Netty 4.2's `MultiThreadIoEventLoopGroup` with
`NioIoHandler.newFactory()`. Socket options apply to all three listeners. Tune counts
and backlog using measurements from your workload; more threads do not by
themselves establish higher throughput.

### Protocol Selection

Only a case-insensitive exact `JSON` string selects the JSON TCP protocol.
Every other string, including a typo or a value containing surrounding whitespace,
selects SBE. Use `JSON` or `SBE` explicitly.

WebSocket always uses its WebSocket JSON event codec. HTTP API routing and health
or metrics JSON are independent of `protocol.type`. `StringProtocol` and
`MessageBufferProtocol` exist for programmatic use, but the default module does not
select them through this property.

### Admission and Socket Budgets

```properties
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

# Protocol Frame Limits
protocol.maximum.frame.bytes=65535
```

These limits are consumed by the default Dagger service and TCP/WebSocket login
providers. Nonpositive count/byte/login budgets, queue capacities below 16, negative read-idle
durations and frame limits outside 8..65535 fail initialization. Malformed integer strings follow the loader's ordinary fallback rule.
Budgets are not automatically resized or reloaded. Session budgets are fixed
before the first shared dispatcher or session is created. Starting another context
with conflicting live session budgets fails; complete shared shutdown is terminal.
The bundled two-shard setting overrides the CPU-derived fallback. Programmatic
constructors accept custom count and long byte budgets.

Payload budgets are estimates rather than exact JVM-memory ceilings. Native and
binary sources use readable byte counts; decoded JSON captures a conservative
graph estimate, and custom Event implementations can override `getPayloadSize()`.
The default estimate for an unknown application object is 256 bytes; provide a
conservative override for larger objects. Task counts also bound zero-byte events.

The session limit applies to registered credentials, including logins awaiting
protocol initialization. At capacity, a new credential fails login while existing
players remain registered. A reconnect for an existing credential replaces its
session using the same slot. Online registry entries have no size-based eviction
or cache TTL. Explicit closure and configured read-idle expiry release sessions.
TCP and WebSocket share the socket slot budget even before authentication; HTTP
monitoring connections do not consume it. Login expiry covers upgrade and session
initialization. An authenticated connection cancels that deadline while preserving
its read-idle detector. Incoming bytes reset the read-idle timer; this is transport
inactivity detection, not an application heartbeat protocol.

Socket write and encoded-byte budgets work alongside Channel write-buffer water
marks. Overflow or a nonwritable write fails the write and closes that connection;
there is no unlimited retry queue. Writes drain in FIFO batches of at most 64 and
flush once per batch. One Game send caches encoding per wire format, sharing JSON
application frames between TCP and WebSocket recipients. Inbound reads pause above
count or byte high water marks and resume from low-water notifications. The bridge's default five-second pause deadline
is separate from callback execution and is only customizable programmatically.
See [backpressure](EVENT_LOOP.md#network-backpressure) for ownership and recovery.

### Queue Sampling

```properties
metrics.event.interval.seconds=5
```

A positive value schedules sampling immediately and then at that interval. Zero
or a negative value disables scheduling. The legacy sampler reads shared dispatcher
queues only. Live `/metrics/flow-control` and Prometheus flow series separately
include the Game, byte budgets, admission outcomes and timing bucket bounds; they
remain available with sampling disabled. See
[metrics architecture](METRICS_ARCHITECTURE.md) for cached values and shutdown.

## Identifier Node Names

`IdGeneratorService.NODE_NAME` defines the JVM system-property key `ZeaNode`.
Applications calling `IdGeneratorService.generate()` can pass
`-DZeaNode=game-1` to produce identifiers such as `game-1-42`. An absent or empty
value produces a numeric identifier. The method reads the system property on each
call; `ConfigurationManager` does not load this setting from `conf.properties`.

`generateFor(Class<?>)` uses the supplied class's simple name and ignores the node
property. Default Session and Game identifiers use this method, so `-DZeaNode`
does not alter their identifiers. Both generation methods share one process-wide
atomic counter.

## Properties Without a Consumer

The bundled `reconnect.delay=300000` entry is not read by the current implementation.
It does not schedule reconnect attempts, retain disconnected sessions for five
minutes or change cache expiration.

`-Dtcp.port=9090` and `-Dserver.http.enabled=false` do not automatically override
loaded properties. The framework also does not interpret `-Denv` or `-Dconfig.path`.
An application can explicitly read such properties in its own entry point.

## Custom Configuration

Add resources such as `props/conf-dev.properties` and `props/conf-prod.properties`
to your application. The following complete entry point selects one using an
application-defined system property:

```java
package com.example;

import com.akakata.CommandLine;
import com.akakata.context.ServerContext;

public final class ConfiguredServer extends CommandLine {
    public static void main(String[] args) {
        new ConfiguredServer().run(args);
    }

    @Override
    protected ServerContext createServerContext() throws Exception {
        String resource = System.getProperty("zea.config", "props/conf-dev.properties");
        ServerContext serverContext = new ServerContext(resource);

        // Optional explicit override for this application only.
        String httpPort = System.getProperty("zea.http.port");
        if (httpPort != null) {
            serverContext.getConfigManager().getProperties()
                    .setProperty("http.port", httpPort);
        }
        return serverContext;
    }
}
```

Pass `-Dzea.config=props/conf-prod.properties` and optionally
`-Dzea.http.port=18081` to this entry point's JVM. These options work because the
example reads them explicitly. Network groups are created in the context
constructor; changing thread-count properties afterward cannot resize them.
Listener settings and protocol selection must be changed before `initialize()`
constructs the Dagger components.

### HTTP-Only Configuration

```properties
server.tcp.enabled=false
server.http.enabled=true
server.websocket.enabled=false
http.port=18081
bossThreadCount=1
workerThreadCount=2
so.backlog=100
so.reuseaddr=true
so.keepalive=true
tcp.nodelay=true
metrics.event.interval.seconds=5
```

The repository's `com.akakata.HttpOnlyServer` loads
[src/test/resources/conf-http-only.properties](../src/test/resources/conf-http-only.properties)
and listens on 18081. Its resource omits the sampling property and therefore uses
30 seconds. Launch it using the [README example classpath](../README.md#run-the-repository-example)
with `com.akakata.HttpOnlyServer` as the main class.

### WebSocket-Only Configuration

```properties
server.tcp.enabled=false
server.http.enabled=false
server.websocket.enabled=true
web.socket.port=18300
bossThreadCount=1
workerThreadCount=2
```

The repository's `com.akakata.WebSocketOnlyServer` uses
[src/test/resources/conf-websocket-only.properties](../src/test/resources/conf-websocket-only.properties).
Disabling HTTP also removes access to the monitoring endpoints. The WebSocket
upgrade path remains `/`.

## Wire Formats

All opcode values below are bytes. The JSON decoders expect JSON objects that can
be deserialized into `HashMap<String, Object>`.

### TCP Framing

Every TCP packet begins with an unsigned, two-byte **big-endian** length equal to
the number of following bytes. The prefix excludes itself. The default graph uses
`protocol.maximum.frame.bytes` during login and after authentication, adding two
bytes to the Netty frame decoder limit for the prefix. The wire format limits the
application frame to 65,535 bytes. The standalone three-argument login initializer
retains its 1,024-byte total login-frame default; the configured graph supplies the
explicit limit.

### Login

| Transport | Login Request Body | Success Body | Failure Body |
| --- | --- | --- | --- |
| TCP JSON mode | `0x11` followed by credential bytes | `0x14`, four-byte big-endian UTF-8 token length, token bytes | `0x15` |
| TCP SBE mode | SBE-style header with template ID `0x11`, then credential bytes | Same opcode-and-token body as TCP JSON mode | `0x15` |
| WebSocket | Binary frame with `0x11` followed by credential bytes | Binary frame containing only `0x14` | Binary frame containing only `0x15` |

TCP responses retain the outer TCP length prefix. Login success responses are
written before the session's game protocol is installed, including in SBE mode.
WebSocket success currently does not serialize the token generated by `LoginService`.
Login failure closes the connection after the failure response is written.

`SimpleCredentialsVerifier` ignores the payload and creates fresh credentials.
An application verifier must validate credentials and return a stable key when it
needs existing-session replacement for the same identity.

### TCP JSON After Login

```text
frame length: uint16 big-endian
opcode: uint8
JSON byte length: int32 big-endian
JSON bytes: UTF-8
```

The encoder emits only an opcode when the source is null, but the decoder expects
a length-prefixed JSON object. Use an object payload for messages intended to be
decoded by this codec. The inner length must be positive and exactly match the
remaining bytes. JSON roots must be objects with no trailing JSON values. After
login, the inbound bridge accepts `SESSION_MESSAGE`, `LOG_OUT` and `DISCONNECT`;
server-only opcodes such as TCP `NETWORK_MESSAGE` close the connection.

### TCP SBE-Style Envelope After Login

```text
frame length: uint16 big-endian
blockLength: uint16 little-endian
 templateId: uint16 little-endian
   schemaId: uint16 little-endian, emitted as 1
    version: uint16 little-endian, emitted as 1
payload: blockLength bytes
```

The eight-byte header is an SBE-style envelope. There is no generated application
schema codec in this repository. The encoder copies readable `ByteBuf` contents,
copies byte arrays directly, encodes strings as UTF-8 and serializes other objects
as JSON. Serialization failure fails the write; null sources produce an empty payload.

The decoder requires a complete header, `schemaId=1`, `version=1`, `templateId`
between 0 and 255, and block length equal to all remaining bytes. Truncated or
trailing payloads fail decoding. Authenticated opcode admission follows the same
bridge policy as TCP JSON.
The outer TCP size limit leaves at most 65,527 bytes for an SBE payload.

### WebSocket After Login

```text
binary WebSocket frame payload:
opcode: uint8
JSON byte length: int32 big-endian
JSON bytes: UTF-8
```

There is no extra two-byte TCP prefix. Incoming `NETWORK_MESSAGE` (`0x34`) is mapped
to `SESSION_MESSAGE` (`0x33`). As with TCP JSON, the encoder can emit a null-source
opcode-only message, while the normal decoder expects a JSON object.

The initializer uses a 65,536-byte HTTP aggregator for upgrades. Its WebSocket
protocol configuration accepts path `/`, enables extensions and bounds frame
payloads using `protocol.maximum.frame.bytes`. The compression handler receives
the same positive allocation limit. Application and login frames must be complete
binary frames; fragmented application messages are rejected.

## Fixed Session and Dispatcher Settings

| Setting | Current Value or Rule |
| --- | --- |
| Session registry TTL and size eviction | None; explicit lifecycle cleanup |
| Session admission budget | `session.maximum`, default 10,000 credential slots |
| Periodic cache cleanup | Every minute; enabled by the default `SessionService` constructor |
| Shared dispatcher shard count | `session.dispatcher.shards`; bundled value 2, CPU-derived fallback |
| Shared session pending-task capacity | `session.queue.capacity`, default 32,768 tasks per executor |
| Game pending-task capacity | `game.queue.capacity`, default 32,768 tasks |
| Inbound queue high / low thresholds | 75% / below 50% of task and payload-byte budgets |
| Sustained inbound read-pause deadline | Five seconds; custom bridge constructor can change it |
| Application Game count in the default context | One |
| Application Game business executors | One, separate from the session shards |
| Handler execution | Serial on the assigned business executor |
| Callback timeout | None; callbacks must return promptly |
| HTTP request aggregation | 65,536 bytes |
| Static-file root | `html` under the process working directory |

Settings without a property name in this table remain fixed or programmatic.
Registry lifetime is independent of cache access; configured transport read-idle
expiry closes the socket and releases its session. WebSocket application messages
must be complete binary frames. Fragmented application/login frames are rejected;
WebSocket frame parsing and compression allocation both use the configured limit.
See [event loops and lifecycle](EVENT_LOOP.md) for ownership, admission, saturation
and shutdown behavior.

## Troubleshooting

- **Missing resource:** package the properties file on the classpath and pass its
  resource name, such as `props/conf-prod.properties`.
- **Unexpected fallback:** check integer formatting, boolean spelling and the
  exact `protocol.type` string. Unknown property names are not rejected.
- **No listeners:** inspect the three enable flags and startup logs. A context can
  intentionally run with all listeners disabled.
- **Port already in use:** inspect the listening process or select another port;
  there is no automatic retry on another fixed port.
- **Monitoring unavailable:** verify `server.http.enabled` and `http.port`.
- **Queue growth or dropped events:** inspect per-shard sampled depths and handler
  work; thread count and protocol changes require workload-specific measurement.

### Launching the Examples

Use JDK 21 or later. The library uses Netty for both I/O and business event
execution; it does not require additional JVM module-access options. In IntelliJ
IDEA, select `com.akakata.TestServer` as the main class and use the test classpath.
Maven launches use:

```bash
mvn test-compile exec:java -Dexec.mainClass=com.akakata.TestServer
```

See the [launch instructions](../README.md#run-in-intellij-idea).
