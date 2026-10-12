# Configuration Guide

This guide describes the configuration consumed by Zea TCP Transport 0.8.3,
including component, security and expanded resource policies.
Dependency versions and build commands are listed in the [README](../README.md).

## Loading Configuration

`ServerContext()` loads the classpath resource `props/conf.properties`, supplied by
[src/main/resources/props/conf.properties](../src/main/resources/props/conf.properties).
`ServerContext(String)` selects another classpath resource name. These constructors
do not read an arbitrary filesystem path or URL.

`ConfigurationManager` uses `Properties.load(InputStream)` and closes the stream.
A missing resource raises `IOException`. Property names are case-sensitive. The
loader does not read YAML, merge environment variables, apply general `-D` overrides
or reload a file after startup. The token service separately reads the one
environment variable explicitly named by `security.token.key.environment`.
See [core components and security](CORE_COMPONENTS.md) for provider replacements.

| Getter | Missing Value | Present Value |
| --- | --- | --- |
| `getInt(key, defaultValue)` | Supplied default | Trimmed integer; malformed or overflowing values throw |
| `getLong(key, defaultValue)` | Supplied default | Trimmed long; malformed or overflowing values throw |
| `getBoolean(key, defaultValue)` | Supplied default | Trimmed, case-insensitive `true` or `false`; other values throw |
| `getString(key, defaultValue)` | Supplied default | Original property string, without trimming |
| `getRequiredString(key)` | Throws | Trimmed nonblank string; blank values throw |
| `getRequiredInt(key)` | Throws | Trimmed integer; blank, malformed or overflowing values throw |
| `getProperties()` | Not applicable | Live mutable `Properties` object |

## Supported Properties

An optional property uses its **fallback** only when absent. A malformed present
numeric or boolean value raises `IllegalArgumentException` naming its key. All
listener ports and bind addresses are required and have no fallback.
The **bundled value** is the value in this checkout's default resource.

| Property | Fallback | Bundled Value | Effect |
| --- | --- | --- | --- |
| `server.tcp.enabled` | `true` | `true` | Start the TCP listener through `startServers()` |
| `server.http.enabled` | `true` | `true` | Start the HTTP listener through `startServers()` |
| `server.websocket.enabled` | `true` | `true` | Start the WebSocket listener through `startServers()` |
| `tcp.port` | Required | `8090` | TCP bind port |
| `http.port` | Required | `8081` | HTTP bind port |
| `web.socket.port` | Required | `8300` | WebSocket bind port |
| `tcp.bind.host` | Required | `0.0.0.0` | TCP bind address |
| `http.bind.host` | Required | `127.0.0.1` | Private monitoring bind address |
| `web.socket.bind.host` | Required | `0.0.0.0` | WebSocket bind address |
| `security.allow.anonymous` | `false` | `false` | Explicit demonstration authentication; production supplies a verifier |
| `security.token.key.environment` | Empty | Empty | Name of a Base64 AES secret environment variable; empty selects an ephemeral instance key |
| `security.token.ttl.seconds` | `3600` | `3600` | Positive token validity in whole seconds; malformed values fail initialization |
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
| `socket.maximum.connections` | `20000` | `20000` | Shared TCP/WebSocket/HTTP socket slots including unauthenticated clients |
| `socket.login.timeout.millis` | `30000` | `30000` | Positive connection-to-authentication deadline, including WebSocket upgrade |
| `socket.read.idle.millis` | `120000` | `120000` | Read inactivity expiry; zero disables it |
| `socket.maximum.pending.writes` | `1024` | `1024` | Positive maximum admitted socket writes awaiting completion |
| `socket.maximum.pending.bytes` | `1048576` | `1048576` | Positive maximum encoded bytes awaiting socket completion |
| `socket.maximum.pending.payload.bytes` | `1048576` | `1048576` | Positive estimated source bytes admitted before encoding, held until write completion |
| `socket.total.pending.bytes` | `268435456` | `268435456` | Aggregate source plus encoded reservations across all context sockets |
| `http.maximum.connections` | `256` | `256` | HTTP-specific socket allowance within the shared total |
| `http.maximum.request.bytes` | `65536` | `65536` | Positive aggregated HTTP body limit |
| `http.request.timeout.millis` | `10000` | `10000` | Absolute request deadline, renewed after each complete body |
| `http.read.idle.millis` | `30000` | `30000` | HTTP read inactivity expiry; zero disables it |
| `business.blocking.threads` | `2` | `2` | Positive worker count for detached blocking operations |
| `business.blocking.queue.capacity` | `256` | `256` | Positive queued-operation allowance, excluding running workers |
| `business.blocking.queue.bytes` | `67108864` | `67108864` | Positive declared retained bytes for queued and running operations |
| `business.blocking.shutdown.millis` | `5000` | `5000` | Nonnegative maximum worker-pool close wait |
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
Supply all three port/address pairs even when one or more listeners are disabled;
the component validates every listener during initialization.
All flags may be false when using the context without network listeners.

The overload `startServers(flashPort, tcpPort, httpPort, webSocketPort)` starts
listeners for positive supplied ports and does not consult the enable flags.
`flashPort` is unused. Bind hosts use `tcp.bind.host`, `http.bind.host` and
`web.socket.bind.host`; all three must be set explicitly. The bundled file
chooses loopback for HTTP and IPv4 wildcard for the game listeners. Explicit port overrides preserve these hosts. Port zero delegates ephemeral-port
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

These limits are consumed by the default Dagger service and TCP/WebSocket login
providers. Nonpositive count/byte/login budgets, queue capacities below 16, negative read-idle
durations and frame limits outside 8..65535 fail initialization. Malformed numeric and boolean strings fail initialization instead of substituting defaults.
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
TCP, WebSocket and HTTP share the total socket slot budget even before
authentication. HTTP also has its own `http.maximum.connections` allowance and
uses absolute request/body and read-idle deadlines instead of a login deadline.
Partial bytes do not extend its request deadline. Login expiry covers upgrade and session
initialization. An authenticated connection cancels that deadline while preserving
its read-idle detector. Incoming bytes reset the read-idle timer; this is transport
inactivity detection, not an application heartbeat protocol.

Socket write, source-estimate and encoded-byte budgets work alongside Channel
write-buffer water marks. Source admission occurs before retaining or encoding a
message. The shared aggregate owner charges sources and encoded bytes together
while both exist. Broadcast sources are charged per recipient as logical pressure.
Login, upgrade and direct HTTP pipeline responses participate in encoded admission.
Native source and encoded reservations return on promise completion or closure.
Neither aggregate admission nor payload estimates bound kernel buffers or exact RSS.
Hard budget overflow fails the write and closes that connection. An unwritable
channel pauses the existing bounded FIFO and resumes on writability notification.
Writes drain in batches of at most 64, with earlier flushing at a 32 KiB source
estimate or water-mark transition. A single timer bounds the oldest queued or
forwarded write to five seconds by default; the four-argument sender constructor
can change that deadline. No unlimited retry queue is created.
Multi-recipient Game sends cache encoding per wire format, sharing JSON
application frames between TCP and WebSocket recipients. Inbound reads pause above
count or byte high water marks and resume from low-water notifications. The bridge's default five-second pause deadline
is separate from callback execution and is only customizable programmatically.
See [backpressure](EVENT_LOOP.md#network-backpressure) for ownership and recovery.

### Resource Budget Coverage

Budget bytes use long values for Session/Game payload storage, admitted socket
sources, aggregate writes and blocking operations. Task, frame and HTTP body counts
retain their integer ranges. Malformed present numeric and boolean values fail
initialization; absent optional resource properties use their declared defaults. Positive/nonnegative range constraints
are checked by resource constructors before listeners open.

| Resource | Admission Coverage |
| --- | --- |
| Session registry | Credential slots, including unfinished logins |
| Session shards | Per-shard pending tasks and queued/running payload estimates |
| Game | Pending tasks and queued/running payload estimates |
| Accepted sockets | TCP + WebSocket + HTTP total, with an additional HTTP sublimit |
| Socket sources | Per-connection pending count and estimated source bytes |
| Encoded transport | Per-connection and shared aggregate bytes, including HTTP/login |
| HTTP input | Per-request body size, absolute deadline and read inactivity |
| Blocking business work | Fixed workers, queued operations and running/queued declared bytes |

A conservative planning allowance includes `shards * session.queue.bytes +
game.queue.bytes + socket.total.pending.bytes + business.blocking.queue.bytes`,
plus HTTP aggregation, decoding, application state, caches, thread stacks and JVM
baseline storage. Charges can overlap for shared payloads, and estimates are not
an exact memory ceiling. HTTP aggregated bodies alone may approach
`http.maximum.connections * http.maximum.request.bytes`; header and codec storage
are additional. Set deployment heap/container budgets separately and measure your
workload. See [slow operations](SLOW_BUSINESS.md) for safe offloading and cancellation.

### Queue Sampling

```properties
metrics.event.interval.seconds=5
```

A positive value schedules sampling immediately and then at that interval. Zero
or a negative value disables scheduling. The legacy sampler reads shared dispatcher
queues only. Live `/metrics/flow-control` and Prometheus flow series separately
include the Game, byte budgets, admission outcomes and timing bucket bounds; they
remain available with sampling disabled. See
[monitoring](MONITORING.md) for cached values and
[event lifecycle](EVENT_LOOP.md) for shutdown ownership.

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
tcp.port=8090
http.port=18081
web.socket.port=8300
tcp.bind.host=0.0.0.0
http.bind.host=127.0.0.1
web.socket.bind.host=0.0.0.0
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
tcp.port=8090
http.port=8081
web.socket.port=18300
tcp.bind.host=0.0.0.0
http.bind.host=127.0.0.1
web.socket.bind.host=0.0.0.0
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

JSON output streams directly into a native application frame with a maximum
capacity of 65,535 bytes. It does not create a whole-payload heap byte array.
The encoder patches the inner payload length after successful serialization and
releases partial output on serialization failure or size overflow. Native capacity
may grow while encoding and can exceed readable bytes within this frame ceiling;
socket byte reservations count readable encoded bytes, not allocator capacity.

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

Default login is denied unless an application supplies `CredentialsVerifier` or
explicitly opts into demonstration mode with `security.allow.anonymous=true`.
`SimpleCredentialsVerifier` ignores the payload and creates fresh credentials.
An application verifier must validate credentials and return a stable key when it
needs existing-session replacement for the same identity. See
[security configuration](CORE_COMPONENTS.md#authentication-defaults) for token keys,
expiry, compatibility and deployment exposure.

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
| HTTP request aggregation | `http.maximum.request.bytes`, default 65,536 body bytes |
| HTTP connection allowance | `http.maximum.connections`, default 256 within the shared socket total |
| Detached blocking pool | Configurable worker/task/byte admission; results return through existing event queues |
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
- **Invalid configuration:** check the property named by the exception. Listener
  ports and addresses must be present and nonblank; ports must be integers in
  0..65535 and hosts must resolve. Present numeric and boolean values never fall
  back after parse failure. Unknown application property names are not rejected.
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
