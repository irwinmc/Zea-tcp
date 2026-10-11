# Metrics Architecture

Zea TCP Transport 0.8.0 exposes two process-wide collectors:
`ServerMetrics` for explicit counters and direct runtime readings, and
`EventDispatcherMetrics` for sampled shared-dispatcher queue depths.
[Monitoring](MONITORING.md) describes their HTTP output.

## Data Flow

```text
Application instrumentation -- record*() --> ServerMetrics atomic counters
JVM management beans ---------------------> ServerMetrics runtime getters
AbstractNettyServer.ALL_CHANNELS ----------> ServerMetrics channel count

EventDispatchers shared queues -- scheduled sampling --> EventDispatcherMetrics

ServerMetrics -----------+--> MetricsHandler --> JSON / Prometheus text
EventDispatcherMetrics --+                    --> queue JSON endpoint
ServerMetrics --------------> HealthCheckHandler --> local health checks
```

Both collectors use static singleton instances accessed by `getInstance()`, rather
than context-specific Dagger scopes. Metrics from multiple applications in the
same JVM are not isolated.

## ServerMetrics

Counters use `AtomicLong`. Recording operations are thread-safe individually,
but reading several getters does not create a transactional snapshot.

| Recording Method | Counter Effect |
| --- | --- |
| `recordConnection()` | Increment total and active connections |
| `recordDisconnection()` | Decrement active connections |
| `recordMessageReceived()` | Increment received messages |
| `recordMessageSent()` | Increment sent messages |
| `recordBytesReceived(long)` | Add supplied received-byte count |
| `recordBytesSent(long)` | Add supplied sent-byte count |
| `recordError()` | Increment errors |

The current default network handlers and senders do not call these methods.
Applications must add instrumentation at appropriate boundaries; otherwise the
connection, traffic and error counters remain zero. A disconnection decrements
without clamping at zero, so pair it with exactly one recorded connection.
Decide whether bytes describe transport frames or application payloads and keep
that definition consistent. Count successful sends at write completion when that
is the intended meaning of the sent counters.

`reset()` zeros all counters, including active connections, without changing the
collector's start time. Use it only when instrumentation is coordinated; resetting
while live connections later disconnect can produce negative active counts.

### Direct Runtime Readings

| Getter | Source and Meaning |
| --- | --- |
| `getCurrentChannelCount()` | Size of the static `ALL_CHANNELS` group |
| `getUptimeMillis()` / `getUptimeSeconds()` | Time since singleton construction, not listener bind time |
| `getUsedMemoryMB()` / `getMaxMemoryMB()` | Heap usage and maximum from `MemoryMXBean`, truncated to MiB |
| `getThreadCount()` | `ThreadMXBean` live platform-thread count; excludes virtual threads |
| `getCpuLoad()` | `com.sun.management.OperatingSystemMXBean.getCpuLoad()`, system CPU fraction |

The channel group contains listener channels and TCP channels explicitly added by
`LoginHandler.channelActive()`. It does not track all HTTP or WebSocket client
channels and is not interchangeable with the instrumented active-connection
counter. CPU can be negative when unavailable; the implementation requires the
JVM's management bean to implement the `com.sun.management` interface.

### Add Inbound Instrumentation

This complete handler demonstrates an application-owned instrumentation point;
it is not installed by the default initializer:

```java
package com.example;

import com.akakata.metrics.ServerMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

public final class InboundMetricsHandler extends ChannelInboundHandlerAdapter {
    private final ServerMetrics metrics = ServerMetrics.getInstance();

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        metrics.recordConnection();
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        metrics.recordDisconnection();
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
        metrics.recordMessageReceived();
        if (message instanceof ByteBuf buffer) {
            metrics.recordBytesReceived(buffer.readableBytes());
        }
        super.channelRead(ctx, message);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        metrics.recordError();
        super.exceptionCaught(ctx, cause);
    }
}
```

Placement determines whether a received object represents a socket read, a frame
or an application event. Place byte accounting where bytes are visible, and
message accounting after the appropriate decoder. This handler forwards messages
and leaves their ownership unchanged. Account for pipeline replacement after TCP
login if installing custom handlers there.

## EventDispatcherMetrics

The collector owns a one-thread `ScheduledThreadPoolExecutor` whose thread is a
**daemon platform thread** named `event-dispatcher-metrics`.

`ServerContext.initialize()` reads `metrics.event.interval.seconds` with fallback
30 and calls `start(intervalSeconds)`. A positive interval schedules a fixed-rate
task with zero initial delay. A nonpositive interval leaves sampling disabled.
The sampler reads:

- Aggregate shared queue size from `EventDispatchers.sharedQueueSize()`.
- Individual depths from `EventDispatchers.sharedShardQueueSizes()`.
- The successful sampling timestamp in epoch milliseconds.

The shared shard count is `max(2, highestOneBit(availableProcessors))`. Sampling
excludes game-owned or other dedicated dispatchers. Queue values count pending Netty tasks, excluding running callbacks and execution
time. Cancelled task shells can remain in a physical queue until the worker reaches
them, although their event, session and payload references have already been cleared.

| Getter | Behavior |
| --- | --- |
| `getTotalQueueSize()` | Cached aggregate; zero before a successful sample |
| `getPerShardQueueSizes()` | Defensive copy of cached array; empty before sampling |
| `getShardCount()` | Live shared dispatcher shard count |
| `getMaxShardQueueSize()` | Maximum cached depth; zero for an empty array |
| `getMinShardQueueSize()` | Minimum cached depth; zero for an empty array |
| `getAverageShardQueueSize()` | Integer mean, truncated; zero for an empty array |
| `getLastUpdateTimestamp()` | Successful sample time; zero before sampling |
| `isStarted()` | Whether start was requested with a positive interval; remains true after stop |

Total depth and the array are sampled and published separately. HTTP serialization
also calls getters independently, so the fields can briefly reflect different
observations. Check freshness and trends instead of assuming a single atomic queue
snapshot. A stale sample can miss a short queue spike.

Game admission reports, dispatcher rejection counters and per-socket pending-write
counts are direct application APIs. They are not collected by this sampler or
exported in the current HTTP metric schemas. Socket backpressure may close a
connection even after its recipient queue accepted an event; admission counts
must not be interpreted as successful network delivery.

### Configuration and Lifecycle

```properties
server.http.enabled=true
http.port=8081
metrics.event.interval.seconds=5
```

Configure the interval before context initialization. Starting again does not
replace the initial positive interval. `stop()` calls `shutdownNow()` on the
scheduler; it does not reset the started flag or cached values. The singleton
scheduler cannot be restarted after shutdown.

`ServerContext.close()` stops sampling after game and session-service cleanup and
before shared-dispatcher shutdown. Applications using a standalone collector are
responsible for calling `stop()` at the end of their process lifecycle. Merely
reading `getShardCount()` can initialize the process-wide shared dispatcher if it
has not already been initialized.

## HTTP Serialization

`MetricsHandler` uses Jackson 3.2.3's `tools.jackson.databind.ObjectMapper`.
Its `/metrics` response includes server, connection, traffic, system and error
groups. Queue metrics are exposed only through `/metrics/event-dispatcher` and
currently have no Prometheus entries.

Heap and traffic fields with the `_mb` suffix use integer division by 1,024 squared.
CPU output is rounded to two decimal places. Prometheus heap-byte values are
reconstructed from these truncated heap MiB getters, so they have MiB precision.
See the [endpoint reference](MONITORING.md) for exact field names and metric types.

## Extending Monitoring

There is no dynamic collector registry or per-application metric namespace in the
current implementation. To expose an application metric, maintain its counter in
application code, add a route and serialization path, and wire the handler through
the server module or an application-owned initializer. Preserve the existing
endpoints when replacing a handler.

When using `AbstractHttpHandler.sendHttpResponse()` for a custom endpoint, release
a temporary source `ByteBuf` after the helper returns: it copies readable bytes
into the response and does not release the supplied source reference. If forwarding
a native message through Netty instead, follow that write path's ownership rules.

For collector tests, isolate process-wide shutdown in a separate JVM. Do not stop
the singleton sampler and then expect a later test or context to restart it.
Likewise, resetting global counters in concurrently running tests requires
coordination.

## Source References

- [ServerMetrics](../src/main/java/com/akakata/metrics/ServerMetrics.java)
- [EventDispatcherMetrics](../src/main/java/com/akakata/metrics/EventDispatcherMetrics.java)
- [MetricsHandler](../src/main/java/com/akakata/server/http/MetricsHandler.java)
- [HealthCheckHandler](../src/main/java/com/akakata/server/http/HealthCheckHandler.java)
- [ServerContext](../src/main/java/com/akakata/context/ServerContext.java)
- [Event loops and lifecycle](EVENT_LOOP.md)
