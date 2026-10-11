# Configuration Guide

This guide describes the configuration consumed by Zea TCP Transport 0.7.8.
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

These values are read by
[ServerContext](../src/main/java/com/akakata/context/ServerContext.java),
[ServerModule](../src/main/java/com/akakata/context/module/ServerModule.java),
[ProtocolModule](../src/main/java/com/akakata/context/module/ProtocolModule.java) and
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

### Queue Sampling

```properties
metrics.event.interval.seconds=5
```

A positive value schedules sampling immediately and then at that interval. Zero
or a negative value disables scheduling. The sampler reads shared dispatcher
queues only; it does not include a game's dedicated dispatcher. See
[metrics architecture](METRICS_ARCHITECTURE.md) for cached values and shutdown.

## Properties Without a Consumer

The bundled `reconnect.delay=300000` entry is not read by the current implementation.
It does not schedule reconnect attempts, retain disconnected sessions for five
minutes or change cache expiration. Constants such as login-expiration and
reconnection keys in `ZealConfig` do not establish runtime configuration support
unless a component actually consumes them.

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
the number of following bytes. The prefix excludes itself. The login frame decoder
has a maximum total frame size of 1,024 bytes. Post-login decoders have a larger
configured maximum, but the two-byte prefix still limits the body to 65,535 bytes.

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
decoded by this codec. Incoming opcodes are preserved.

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
encodes strings as UTF-8, and serializes other objects as JSON, falling back to
`toString()` if JSON serialization fails. Null sources produce an empty payload.

The decoder maps the low eight bits of `templateId` to the event opcode. It logs
schema/version differences rather than rejecting them, drops frames shorter than
the header, and truncates an oversized declared block length to available bytes.
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

The initializer uses a 65,536-byte HTTP aggregator for upgrades and
`WebSocketServerProtocolHandler("/", null, true)`. Compression uses
`WebSocketServerCompressionHandler(0)`, retaining unlimited decompression allocation
in the current Netty API. These values are code settings, not properties.

## Fixed Session and Dispatcher Settings

| Setting | Current Value or Rule |
| --- | --- |
| Session-cache maximum size | 10,000 entries |
| Session-cache expiration after access | 2 hours |
| Session-cache expiration after write | 24 hours |
| Periodic cache cleanup | Every minute; enabled by the default `SessionService` constructor |
| Shared dispatcher shard count | `max(2, highestOneBit(availableProcessors))` |
| Queue capacity per Agrona dispatcher | 32,768 deliveries |
| Maximum polling batch | 256 deliveries |
| Default handler mode | `CONCURRENT`, using virtual threads |
| Concurrent callback wait | 5,000 milliseconds per event |
| HTTP request aggregation | 65,536 bytes |
| Static-file root | `html` under the process working directory |

These settings are not exposed as properties by the current default modules.
In particular, cache access expiration tracks cache access; it is not a network
idle timeout. See [event loops and lifecycle](EVENT_LOOP.md) for ownership,
saturation and shutdown behavior.

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

### Agrona Access Error During Startup

An `IllegalAccessError` reporting that `org.agrona.UnsafeApi` cannot access
`jdk.internal.misc.Unsafe` means the launching JVM lacks the required module-access
options. Supply these before the Java main class or `-jar` argument:

```text
--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
```

In IntelliJ IDEA, place them in the Application configuration's **VM options**.
The repository provides a shared **TestServer (Agrona)** configuration with both
options. Maven launches use [.mvn/jvm.config](../.mvn/jvm.config); run
`mvn test-compile exec:java -Dexec.mainClass=com.akakata.TestServer` from the
repository root.

Passing these options through `exec.args`, `commandlineArgs` or the IDE's
**Program arguments** sends them to the application's `main(String[])` after JVM
startup and does not grant module access. Surefire's `argLine` applies to forked
test JVMs and does not configure direct IDE application runs. See the
[launch instructions](../README.md#run-in-intellij-idea).
