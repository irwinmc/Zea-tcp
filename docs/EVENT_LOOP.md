# Event Loops, Dispatch and Lifecycle

This guide describes Zea TCP Transport 0.8.0 with Netty 4.2.19.Final and Java 21.
See [configuration](CONFIGURATION.md) for consumed properties and wire formats.

## Resource Layout

```text
ServerContext
  NetworkBootstrap
    shared boss I/O group             TCP / HTTP / WebSocket accepts
    shared worker I/O group           channel I/O and pipelines
  Game (one application instance)
    NettyEventDispatcher              one ordered business executor
    connected player sessions
  SessionService
    Caffeine registry and owned sessions
    periodic cache-cleanup scheduler
  EventDispatcherMetrics
    daemon platform-thread scheduler

EventDispatchers (process-wide)
  shared ShardedEventDispatcher
    DefaultEventExecutorGroup
      fixed session shard 0 ... S-1    one ordered executor per shard
```

The default Dagger graph creates one `Game` for the application. It is an
application session container and a queue for shared business events and
broadcasts. There is no room registry or room-ID routing. `DefaultGame` forwards
input without choosing recipients; register application handlers through
`getEventDispatcher()` and submit shared work with `send()`.

The Game owns its single business executor. Sessions use separate shared business
executors, so a Game callback does not occupy a session shard. Business executors
and network I/O groups are also separate. Their platform threads start lazily and
are not daemon threads; owners must close them.

## Network I/O Groups

`NetworkBootstrap` creates boss and worker `MultiThreadIoEventLoopGroup` instances
with `NioIoHandler.newFactory()`. The thread prefixes are `netty-boss` and
`netty-worker`. `ServerModule` assigns these same groups to TCP, HTTP and WebSocket
listeners; the default graph does not allocate an I/O group per protocol.

The bundled properties request two boss and two worker threads. Missing properties
fall back to one boss thread and the JVM's available processor count for workers.
Sharing groups means the protocols can affect one another's I/O latency.

Pipeline decoding, login initialization and HTTP routing still run on I/O threads.
Only submitted business events move to a business executor. Keep pipeline
callbacks short and avoid blocking database or network calls there.

`NetworkBootstrap.close()` requests shutdown of both I/O groups and waits for both
termination futures. Invoke context or network shutdown from outside the I/O loops.
A standalone `NettyConfig` can allocate its own groups when none are supplied;
its application must establish their shutdown ownership.

## Session Sharding and Routing

The process-wide factory chooses:

```text
S = max(2, Integer.highestOneBit(Runtime.getRuntime().availableProcessors()))
```

A separately constructed `ShardedEventDispatcher(count)` rounds a positive count
up to a power of two. Session IDs must remain immutable.

| Operation | Route or Registration |
| --- | --- |
| `fireEvent(event, session)` | `session.getId().hashCode() & (S - 1)` |
| Unscoped `fireEvent(event)` on the facade | `event.getType() & (S - 1)` |
| Register a `SessionEventHandler` | Its session's fixed shard |
| Register an ordinary `EventHandler` | Every shard |
| `getHandlers(type)` on the facade | Aggregate snapshot, global handlers deduplicated by identity |
| `removeHandler` / `removeHandlersForEvent` on the facade | Remove registrations from every shard |
| `removeHandlersForSession` on the facade | The session's fixed shard |

`DefaultPlayerSession` stores its shared shard at construction.
`DefaultSession.onEvent()` supplies the target session when publishing. Each shard
indexes subscriptions by session object identity and event type; targeted delivery does not scan
other sessions' handlers. Global observers can receive matching targeted events,
while one player's handlers cannot receive another player's targeted events.
Session instances remain separate even if custom `equals()` methods consider their
business identifiers equal; closing one instance cancels only its own deliveries.

An unscoped event on a shard reaches matching global and session subscriptions on
that shard. Unscoped facade events of different types can run on different shards;
they have no common ordering guarantee. Use targeted delivery for session work and
`Game.send()` for ordered application-wide events.

Subscriptions are captured when registered. Remove and register a handler again
if changing its event type or session association. Registering the same handler
instance twice on the same dispatcher is idempotent. Removed subscriptions are
checked again before callback invocation; removal does not interrupt a callback
that has already started.

`EventDispatchers.newDedicatedDispatcher()` creates a separately owned two-shard
group. Close that group when finished. `NettyEventDispatcher` creates one ordered
executor; it starts automatically on submission. Its `initialize()` method only
validates that it is open and is optional.

## Ordering and State Ownership

A `NettyEventDispatcher` invokes matching handlers synchronously on its fixed
executor. One callback must return before the next delivery starts. All event
types for one session share this order. Reentrant submissions join the queue
behind work already accepted; they do not execute inline.

With concurrent producers, ordering follows successful queue publication. There
is no ordering by event timestamp or across separate executors. Global observers
registered on multiple shards can run concurrently and must coordinate shared
state. Several sessions assigned to one shard execute serially on that shard.

The application Game uses one dispatcher for `send()` events, `sendToPlayer()`
unicasts, `sendToPlayers()` selections and `sendBroadcast()` actions. Sends wait for
earlier Game work before submitting targeted outbound events. Each recipient
processes accepted sends in that order on its own session queue. Full broadcasts
observe membership when their action executes. Selected sends copy their collection
at submission, deduplicate session instances and reject players from other Games.
Disconnected selections are skipped. Closure can cancel work that has not started.

The default `SESSION_MESSAGE` callback posts a `PlayerMessageEvent` to the Game.
Its `getPlayerSession()` identifies the authenticated sender; `getSource()` remains
the decoded payload. Register a Game handler for `Events.SESSION_MESSAGE` and choose
recipients explicitly. The default does not echo or broadcast inbound data.

All three outbound Game methods return `CompletableFuture<DeliveryReport>`.
`accepted`, `rejected` and `skipped` describe recipient queue admission, not callback
completion, successful socket writes or client acknowledgment. A rejected open
recipient is closed. Game admission failure or queued shutdown completes the future
exceptionally. Do not wait for these futures inside the Game executor; attach a
completion callback or observe them from an external thread.

Keep session-owned state on its session executor and shared business state on the
Game executor. Post events between these boundaries. Login, connection,
disconnection and direct lifecycle calls still use their existing monitors and
caller threads; creating a Game does not move those methods onto its executor.
Applications should submit shared state updates explicitly rather than assuming
all callbacks already run on the Game thread.

Callbacks must return promptly. Offload blocking work, retain any native payload
needed by that work, and submit its result back to the owning queue. Asynchronous
work started by a callback is outside the queue's serial guarantee until its
completion is posted back. Results can complete in a different order from their
requests; sequence dependent work explicitly. There is no automatic callback
deadline or interruption.
Never wait synchronously for work submitted to the same executor.

## Bounded Queues and Rejection

Each business executor accepts at most **32,768 pending tasks** by default.
Standalone and sharded constructors accept a custom capacity of at least 16.
`game.queue.capacity` configures the default Game's capacity. Shared session shards
still use the default capacity; construct a custom sharded group to choose another.
Sessions on the same shard share its budget. The Game has a separate bounded queue.

`NettyEventDispatcher.fireEvent()` and `execute()` return `true` when accepted
and `false` when closed, saturated, or, for targeted delivery, the session is
closing. Saturation uses Netty's rejecting policy: a callback never falls back to
execution on the submitting thread. `getRejectedEventCount()` counts executor
rejections; submissions ignored after dispatcher closure are excluded.

`EventDispatcher.fireEvent()`, `Session.onEvent()` and `Game.send()` expose
admission through their existing names: `true` means queued, and `false` means
full or closed. `PlayerSession.sendToGame()` forwards the Game's result. These
methods return immediately with the admission result, without waiting for available
capacity or callback completion. There is no automatic retry or overflow queue.
`NettyEventDispatcher.submit()` resolves an action future after payload cleanup and
fails it if admission, execution or queued closure fails. Cancelling a future does
not interrupt or roll back an action.

The default inbound session callback closes the submitting session when the Game
cannot admit its message. A network bridge closes the connection if its session
queue rejects input. Disconnect/error processing has direct cleanup fallback when
the queue cannot accept a lifecycle event.

`getQueueSize()` counts pending deliveries owned by one dispatcher. Shared queue
metrics sample the physical Netty queues, excluding running callbacks and the
Game's independent queue. Cancelled task shells may remain until the worker reaches
them, but their event, session and payload references are cleared immediately.
A zero queue depth does not mean the running callback has completed. See
[monitoring](MONITORING.md#dispatcher-queue-metrics) for sampling behavior.

## Network Backpressure

`DefaultToServerHandler` examines both the assigned session queue and the separate
Game queue. It pauses an enabled `autoRead` flag at 75% pending-task capacity or
when the Channel becomes unwritable. It probes recovery every 10 ms and resumes
reads only below 50% capacity with a writable Channel. It preserves an auto-read
flag that the application disabled before the bridge. Recovery tasks are cancelled
on handler removal or channel closure.

Already decoded input from the current read batch can still arrive while reads are
paused. A full queue closes that connection rather than silently dropping input.
A pause lasting five seconds also closes the connection. This is a pressure deadline,
not a callback execution deadline. The two-argument bridge constructor accepts a
custom positive pause duration; there is no default properties key for that duration.

`SocketMessageSender` caps pending application writes before adding I/O tasks:
`socket.maximum.pending.writes` defaults to 1,024. Every accepted write is scheduled
on the Channel event loop in admission order. The transport-side buffer limiter
also caps encoded pending bytes using `socket.maximum.pending.bytes`, defaulting
to 1,048,576 bytes. Channel write-buffer water marks remain an additional limit.
No retry queue is created. A budget violation, unwritable write or transport-write
failure completes the write exceptionally and closes the connection.

A write future is uncancellable so cancellation cannot release a native source
while an encoder still uses it. Event sources are independently retained until
completion. Raw Netty messages transfer ownership even when admission rejects
them; rejected Event sources remain owned by their caller. Channel closure fails
queued futures and releases their captured references. `pendingWrites()` exposes
the admitted write count but is not exported by the HTTP metrics endpoints.

## Session and Application Lifecycle

Normal connection moves a session through `NOT_CONNECTED`, `CONNECTING` and
`CONNECTED`. `DefaultSession.close()` makes `CLOSED` terminal, disables writes and
detaches the sender. New events return `false` and handlers are ignored after closure starts;
a sender offered afterward is closed immediately.

Login success callbacks check channel activity and session shutdown under the
session monitor before connecting the Game, installing a protocol and registering
handlers. Channel-close listeners also close sessions whose login initialization
is still pending. Failed login or callbacks clean up the session and transport.

Login, Game connection, registry registration and session closure share the
session monitor. Synchronizing on a method parameter locks that session object;
a separate private lock in only one participant would not coordinate these paths.
Avoid unrelated locks and long work while holding the session monitor.
`afterSessionConnect()` runs outside `connectSession()`'s synchronized blocks,
but its login caller may still hold the session monitor.

Session closure snapshots resources under its monitor and cleans up outside it.
External concurrent closers wait for the initiating cleanup. Reentrant callers and
Netty dispatcher callbacks avoid joining a closer that could be waiting for their
own callback. `DefaultPlayerSession.close()` also disconnects from its Game.

`EventDispatchers.release()` recognizes the shared facade and borrowed shards,
including shards of custom groups. Closing one session removes only its handlers
and queued targeted deliveries, preserving the shard for other sessions. A
privately owned dispatcher is closed completely.

`AbstractGame.close()` marks the application Game closed, snapshots membership,
closes players outside the Game monitor, clears membership and closes its own
executor. It continues after individual session failures. External concurrent
closers wait for cleanup; callback or reentrant callers avoid shutdown cycles.
New session creation and connection are rejected after closure starts.

## Payload Ownership

Netty native buffers are reference-counted. Event publication borrows the caller's
reference and retains an additional reference for each accepted delivery.

| Boundary | Ownership Rule |
| --- | --- |
| Session, dispatcher or Game submission | Caller keeps its original reference; each queued owner retains its own |
| Game broadcast | Game task retains once; each accepted recipient delivery retains separately |
| Rejection, cancellation or queued shutdown | Release the abandoned delivery's retained reference |
| Handler or queued action | Borrow the payload until the synchronous callback returns |
| Running callback during close | Keep its retained reference until the callback finishes |
| Asynchronous work started by a handler | Retain and release an additional reference explicitly |
| `DefaultToServerHandler` | Release the inbound native owner after session submission |
| `SocketMessageSender` sending an event | Retain the native source until write completion or failure |
| `MessageBufferEventEncoder` | Copy readable bytes without consuming the borrowed source reference |

Events and payloads are not deep-copied. Do not mutate an event after publication.
Payloads shared among broadcast recipients should be immutable or read-only.
The dispatcher captures the retained native object separately, so cleanup still
releases that original object if application code later changes the event source.

`EventDecoder` and `MessageBufferEventDecoder` copy payload bytes;
`SbeEventDecoder` returns a retained slice. JSON decoders borrow slices for parsing.
The SBE encoder copies readable bytes. End-to-end zero-copy behavior is not
provided for every protocol.

This complete example runs without additional JVM options:

```java
package com.example;

import com.akakata.event.Event;
import com.akakata.event.EventHandler;
import com.akakata.event.Events;
import com.akakata.event.impl.NettyEventDispatcher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class DispatcherExample {
    public static void main(String[] args) throws Exception {
        try (NettyEventDispatcher dispatcher = new NettyEventDispatcher()) {
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
                if (!dispatcher.fireEvent(Events.event(payload, 100))) {
                    throw new IllegalStateException("Event was rejected");
                }
            } finally {
                payload.release();
            }
            if (!handled.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Event was not handled");
            }
        }
    }
}
```

## Migrating to 0.8.0

Use `NettyEventDispatcher` wherever a standalone business dispatcher is needed.
There is no execution-mode parameter, callback worker per event, polling batch or
manual `doWork()` consumer. Initialization is automatic. Existing targeted
`EventDispatcher` and `Session.onEvent()` submissions remain available.

`Game` now extends `AutoCloseable` and exposes `getEventDispatcher()` for application
handler registration. Custom direct implementations must provide that method.
`AbstractGame` supplies it and owns a final dispatcher; register handlers on this
dispatcher rather than replacing it. Game events and broadcasts are asynchronous
and serial, so update code that assumed broadcasting completed before its method
returned. Application lifecycle callbacks retain the caller-thread behavior
described above.

The default inbound behavior also changed: messages no longer imply a full broadcast.
Register a Game business handler and call an explicit send method when needed.
`Game.sendBroadcast()` now returns `CompletableFuture<DeliveryReport>`; callers may
ignore the result, but custom Game implementations must update the return type and
implement the selected-recipient methods. Recompile callers against the new API.
`EventDispatcher.fireEvent()`, `Session.onEvent()`, `Game.send()` and
`PlayerSession.sendToGame()` retain their original names and now return a boolean
admission result. Callers may ignore the result where appropriate; overload-aware
code must handle `false`. Custom implementations must update these return types,
and all callers must recompile against the new API.

## Registry Ownership and Shutdown

`SessionService` registers sessions by `Credentials` with an atomic admission
budget. `session.maximum` defaults to 10,000 credential slots and includes pending
logins. Existing credentials can reconnect while full; a new credential is rejected
with `SessionCapacityException` by `replaceSession()` or false by `putSession()`.
Rejected direct registrations retain caller ownership. `LoginService.login()`
closes the newly created session on rejection and returns null, allowing TCP and
WebSocket login handlers to send their normal login-failure response.

There is no cache-size eviction, access TTL or absolute TTL for online sessions.
The registry remains populated until explicit removal, replacement, session closure
or service shutdown. Applications may install transport idle handlers according to
their own activity policy. The default constructor still schedules cache maintenance
every minute; the boolean constructor flag disables that scheduler.

Replacement closes the previous session synchronously; registering the same
session under the same key does not close it. Conditional removal and close
callbacks compare session identity so an old callback cannot remove its successor.
A separate identity-based set tracks owned sessions even when asynchronous cache
removal notifications are pending. Service shutdown snapshots ownership, stops
cleanup scheduling, closes sessions and invalidates the cache outside its monitor.

Dispatcher closure cancels queued tasks, releases their retained references and
clears subscriptions. Running callbacks retain their references until completion.
External callers wait for owned executor termination; closure inside any dispatcher
callback initiates cleanup without joining executors, avoiding self or cross-group
shutdown cycles. An external owner can call `close()` afterward to await termination.

Netty shutdown uses a zero quiet period and a five-second shutdown timeout. This
is an executor shutdown setting, not a deadline that can interrupt a running
application callback. A callback that never returns can prevent external closure
from finishing. Applications must give blocking work its own cancellation policy.

`ServerContext.close()` stops listeners, closes the Game and SessionService, stops
metrics sampling, closes shared session executors and then shuts down network I/O.
The shared dispatcher and metrics singleton have terminal process-wide lifecycles;
after complete shutdown, start a new JVM rather than trying to restart them.

The local `SimpleLoadTest` counts accepted and rejected publications separately
and waits for a completion barrier. Its throughput and mean latency describe a
local diagnostic run, not a server capacity or tail-latency guarantee.

## Source References

- [NetworkBootstrap](../src/main/java/com/akakata/context/NetworkBootstrap.java)
- [NettyEventDispatcher](../src/main/java/com/akakata/event/impl/NettyEventDispatcher.java)
- [ShardedEventDispatcher](../src/main/java/com/akakata/event/impl/ShardedEventDispatcher.java)
- [EventDispatchers](../src/main/java/com/akakata/event/impl/EventDispatchers.java)
- [AbstractGame](../src/main/java/com/akakata/app/impl/AbstractGame.java)
- [DefaultSession](../src/main/java/com/akakata/app/impl/DefaultSession.java)
- [SessionService](../src/main/java/com/akakata/service/SessionService.java)
