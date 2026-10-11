# Event Loops, Dispatch and Lifecycle

This guide describes the threading and resource ownership of Zea TCP Transport
0.7.8 with Netty 4.2.19.Final, Agrona 2.6.1 and Java 21. Configuration keys are
listed in the [configuration guide](CONFIGURATION.md).

## Resource Layout

```text
ServerContext
  NetworkBootstrap
    shared boss EventLoopGroup       TCP / HTTP / WebSocket accepts
    shared worker EventLoopGroup     accepted-channel I/O and pipelines
  Game (DefaultGame)
    dedicated AgronaEventDispatcher  game-level events
    connected player sessions
  SessionService
    Caffeine registry and owned sessions
    periodic cache-cleanup scheduler
  EventDispatcherMetrics
    daemon platform-thread scheduler

EventDispatchers (process-wide)
  shared ShardedEventDispatcher
    Agrona shard 0 ... shard S-1
      queue + agent platform thread
      virtual-thread callbacks in CONCURRENT mode
```

`ServerModule` assigns the same `NetworkBootstrap` groups to each listener's
`NettyConfig`. No separate I/O group is created for each protocol in the default
Dagger graph. A standalone `NettyConfig` can create its own groups if none are
provided; the caller must establish ownership for those resources.

## Netty I/O Groups

The current `NetworkBootstrap` construction is equivalent to:

```java
package com.example;

import com.akakata.context.NetworkBootstrap;

public final class NetworkGroupsExample {
    public static void main(String[] args) {
        try (NetworkBootstrap bootstrap = new NetworkBootstrap(2, 2)) {
            System.out.println(bootstrap.getBossGroup());
            System.out.println(bootstrap.getWorkerGroup());
        }
    }
}
```

Internally each group uses `MultiThreadIoEventLoopGroup`, a
`DefaultThreadFactory` with prefix `netty-boss` or `netty-worker`, and
`NioIoHandler.newFactory()`. This replaces the deprecated `NioEventLoopGroup` API.

The bundled properties request two boss and two worker threads. Missing properties
fall back to one boss and the JVM's available processor count for workers. These
are group sizes; event-loop threads can be started lazily by Netty. Sharing groups
means HTTP, WebSocket and TCP I/O can affect one another's latency.

Network pipeline work includes decoding, login initialization and HTTP request
handling. Event dispatch moves submitted application callbacks to dispatcher
threads, but it does not move every pipeline operation. Avoid blocking I/O or long
CPU work in Netty callbacks.

`NetworkBootstrap.close()` requests graceful shutdown of both groups before
waiting uninterruptibly for both termination futures. Invoke context or network
group shutdown from a thread outside these event loops.

## Shared Session Shards

The process-wide factory creates:

```text
S = max(2, Integer.highestOneBit(Runtime.getRuntime().availableProcessors()))
```

For example, six available processors yield four shards, eight yield eight, and
one yields two. A separately constructed `ShardedEventDispatcher(count)` rounds a
positive count up to a power of two.

| Operation | Route or Registration |
| --- | --- |
| `fireEvent(event, session)` | `session.getId().hashCode() & (S - 1)` |
| `fireEvent(event)` | `event.getType() & (S - 1)` |
| Register a `SessionEventHandler` | Its session's ID shard |
| Register an ordinary `EventHandler` | Every shard |
| `removeHandlersForSession(session)` on the sharded dispatcher | Search every shard |

`DefaultPlayerSession` resolves and stores its shared Agrona shard at construction,
and `DefaultSession.onEvent()` supplies the target session when submitting. Inside
that shard, session handlers are filtered to the target session; ordinary handlers
remain observers of matching events. Two players sharing a shard therefore do not
receive one another's targeted handler callbacks.

An unscoped `fireEvent(event)` has no target-session filter. Use scoped submission
for player work. `getHandlers()` on the sharded dispatcher selects one shard rather
than aggregating every subscription. For session-handler removal through the
sharded facade, use `removeHandlersForSession`; the typed `removeHandler` route is
based on event type and can differ from the session's registration shard.

`EventDispatchers.newDedicatedDispatcher()` constructs two shards. In contrast,
`AbstractGame` directly creates and initializes one `AgronaEventDispatcher` for
its own game events.

## Agrona Queue and Callback Execution

Each Agrona dispatcher uses a `ManyToOneConcurrentArrayQueue` with capacity
**32,768**, supports multiple publishing threads and has one consumer agent. The
agent processes at most **256** deliveries per work cycle and uses an Agrona
`BackoffIdleStrategy` when idle. Its platform thread is named
`agrona-event-dispatcher` and is not a daemon, so owners must close it.

A standalone `AgronaEventDispatcher` must be initialized explicitly.
`ShardedEventDispatcher` initializes its shards during construction.

| Mode | Callback Execution | Waiting Behavior |
| --- | --- | --- |
| `SERIAL` | Matching handlers run in sequence on the agent thread | A blocking handler blocks that shard |
| `CONCURRENT` (default) | A new virtual thread runs each matching handler | The agent waits up to 5,000 ms for that event's handlers |

Concurrent mode does not provide one reusable worker pool. A callback timeout is
logged but does not interrupt or cancel unfinished handlers; they can overlap with
later deliveries and continue after dispatcher closure. Separate shards run
independently. Application handlers and shared payload objects must therefore
coordinate concurrent access.

Handlers receive the submitted `Event` object. Dispatch metadata records the
session and retained native reference, but there is no deep copy of the event or
its source. Avoid mutating an event after submission. Handler registration uses
concurrent maps and copy-on-write lists; removing a handler does not revoke a
callback that has already been selected or started.

### Queue Saturation

`fireEvent` has no accepted/rejected return value. A full queue logs a warning and
drops the submission. Submission after closure is ignored. Neither operation
transfers the caller's original native-buffer reference. There is no automatic
retry, overflow queue or reliable-delivery acknowledgment.

Sampled queue depths show waiting deliveries rather than all active callbacks.
A zero-depth queue can coexist with running virtual threads. See
[monitoring](MONITORING.md#dispatcher-queue-metrics) for sampling semantics.

## Session and Game Lifecycle

The normal game connection path moves a session through `NOT_CONNECTED`,
`CONNECTING` and `CONNECTED`. `DefaultSession.close()` makes `CLOSED` terminal,
marks shutdown and disables writes. Events and new handlers are ignored afterward,
and a sender offered after closure is immediately closed instead of attached.

Login success write callbacks check channel activity and session shutdown under
`synchronized (session)` before attaching the sender, connecting the game,
installing the protocol and registering login handlers. Channel close listeners
also close sessions whose success response or initialization is still pending.
A failed login or callback cleans up the session and transport.

The session monitor is deliberately shared by login, game connection, registry
registration and session closure. Synchronizing on a method parameter locks the
passed session object; a private lock in only one participant would not coordinate
those operations. Custom code should avoid unrelated locking on the session and
long-running work while holding its monitor. `afterSessionConnect` runs outside
`connectSession`'s own synchronized blocks, but its login caller may still hold the
session monitor.

Session closure detaches its sender and snapshots close listeners under the
monitor, then performs resource cleanup outside that critical section. Reentrant
closure by the closing thread returns; another closing thread waits for completion.
`DefaultPlayerSession.close()` also disconnects from its game.

`EventDispatchers.release()` recognizes both the shared facade and individual
shared shards. It removes the closing session's handlers while leaving the shared
agent and queue available to other players. A privately owned dispatcher is closed.

`AbstractGame.close()` marks the game closed, snapshots connected sessions and
closes them outside its game lock. It then clears membership and closes its
dedicated dispatcher. New creation and connection are rejected once closure begins.

## Session Registry Ownership

`SessionService` caches player sessions by `Credentials` with a maximum of 10,000
entries, expiration after two hours of cache access inactivity and 24 hours after
write. The default constructor schedules cache cleanup every minute on a virtual
thread created for its single-thread scheduled executor.

Replacement closes the previous session synchronously. Registering the same
session under the same key does not close it. Conditional removal and default
session close callbacks compare the cached session by identity, preventing an old
session callback from removing its replacement.

A separate identity-based ownership set tracks sessions even when asynchronous
cache removal notifications are pending. Service shutdown marks the registry
closed and snapshots that ownership, stops the cleanup scheduler with a bounded
wait, closes the owned sessions and invalidates the cache. Cleanup occurs outside
the registry monitor to avoid holding it across session callbacks.

Cache expiration is not an automatic client reconnect mechanism or a transport
idle timeout. Applications define stable credential identities and their own
reconnect policy.

## Payload Ownership

Netty native buffers are reference-counted. Treat event submission as a borrow of
the caller's reference, with additional references retained by asynchronous owners.

| Boundary | Ownership Rule |
| --- | --- |
| `Session.onEvent`, `fireEvent`, game broadcast | Caller retains its original reference; accepted delivery retains its own |
| Queue rejection, cancellation or shutdown drain | Dispatcher releases any reference retained for the abandoned delivery |
| Concurrent callback | Each worker retains a separate native reference and releases it in `finally` |
| Handler callback | Payload is borrowed for callback duration; retain separately to keep it afterward |
| `DefaultToServerHandler` | Releases the inbound native owner after forwarding to the session |
| `SocketMessageSender` sending an `Event` | Retains its native source until write completion or failure |
| `MessageBufferEventEncoder` | Uses a retained duplicate for each write, preserving the source reader index |
| `NettyMessageBuffer` wrapping a `ByteBuf` | Does not add a reference; the caller releases the native buffer when finished |
| `ByteBufHolder` | Owns an existing reference and releases it once on close; do not close it twice |

The dispatcher captures the native object retained at submission. Its cleanup does
not depend on the event still pointing to that same source later. This prevents
cleanup from releasing a replacement source, but does not make payload mutation
safe for concurrent handlers.

`EventDecoder` and `MessageBufferEventDecoder` allocate copied payload buffers.
`SbeEventDecoder` returns a retained slice. JSON decoders borrow input slices only
for parsing. The SBE encoder copies readable bytes; this framework does not provide
end-to-end zero-copy processing for every protocol.

The following complete example releases the producer reference immediately after
submission and lets the dispatcher manage the callback reference:

```java
package com.example;

import com.akakata.event.Event;
import com.akakata.event.EventHandler;
import com.akakata.event.Events;
import com.akakata.event.impl.AgronaEventDispatcher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class DispatcherExample {
    public static void main(String[] args) throws Exception {
        AgronaEventDispatcher dispatcher = new AgronaEventDispatcher(
                AgronaEventDispatcher.ExecutionMode.SERIAL);
        dispatcher.initialize();
        try {
            CountDownLatch handled = new CountDownLatch(1);
            dispatcher.addHandler(new EventHandler() {
                @Override
                public int getEventType() {
                    return 100;
                }

                @Override
                public void onEvent(Event event) {
                    ByteBuf payload = (ByteBuf) event.getSource();
                    System.out.println("Payload bytes: " + payload.readableBytes());
                    handled.countDown();
                }
            });

            ByteBuf payload = Unpooled.buffer().writeInt(42);
            try {
                dispatcher.fireEvent(Events.event(payload, 100));
            } finally {
                payload.release();
            }
            if (!handled.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Event was not handled");
            }
        } finally {
            dispatcher.close();
        }
    }
}
```

## Dispatcher and Context Shutdown

Agrona closure prevents new publication, waits for active publishers to leave,
stops the agent, clears subscriptions and releases queued payload references.
Closure from a handler defers the final shutdown to a virtual thread so it does
not wait for its own callback. The handler marker is shared across dispatcher
instances, covering callbacks that close one another's dispatchers. An external
caller waits for the completion of dispatcher cleanup.

This cleanup does not guarantee that every timed-out concurrent handler has
finished. Application callbacks must cooperate with shutdown and release any
references or resources they independently own.

`ServerContext.close()` stops servers, closes the game and `SessionService`, stops
the queue sampler, closes the shared dispatcher and finally terminates network
groups. Each stage logs cleanup failures so later stages still run. A context that
has not constructed application resources avoids creating the shared dispatcher
just to close it. A closed context cannot be reinitialized.

The shared dispatcher and metrics scheduler are static process-wide resources.
Their shutdown is terminal; use a new JVM for another complete server lifecycle.
Multiple independent application contexts in the same JVM are not isolated from
these shared resources or the static channel group.

## Verification and Tuning

Run the existing regression suite with `mvn test`. It covers targeted delivery on
shared shards, session closure preserving other players, login and registry races,
callback-triggered dispatcher closure, publication during shutdown and native
payload cleanup.

Inspect platform threads with `jcmd <PID> Thread.print`. Use the configured group
sizes and actual thread dumps when estimating thread use: network threads,
Agrona agents, the metrics scheduler and JVM threads all contribute. Virtual
callbacks are not counted by the current `ThreadMXBean` metric.

Use the [local load-test command](../README.md#local-dispatcher-load-test) for a
diagnostic run and inspect dropped-event warnings alongside throughput. This
repository does not publish a reproducible capacity or latency guarantee. Measure
HTTP, TCP and WebSocket workloads independently and together before changing
thread counts or introducing application-specific executor isolation.

## Source References

- [NetworkBootstrap](../src/main/java/com/akakata/context/NetworkBootstrap.java)
- [AgronaEventDispatcher](../src/main/java/com/akakata/event/impl/AgronaEventDispatcher.java)
- [ShardedEventDispatcher](../src/main/java/com/akakata/event/impl/ShardedEventDispatcher.java)
- [EventDispatchers](../src/main/java/com/akakata/event/impl/EventDispatchers.java)
- [DefaultSession](../src/main/java/com/akakata/app/impl/DefaultSession.java)
- [SessionService](../src/main/java/com/akakata/service/SessionService.java)
- [ServerContext](../src/main/java/com/akakata/context/ServerContext.java)
