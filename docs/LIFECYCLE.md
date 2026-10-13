# Lifecycle and Shutdown

This guide describes the current checkout, including
[unreleased changes after 0.8.4](../CHANGELOG.md#unreleased).

## Requesting Shutdown

`ServerContext.closeAsync()` withdraws readiness immediately and requests cleanup.
It returns an independent `CompletableFuture<Void>` observing all cleanup actions.
Cancelling that observation does not cancel owned cleanup. Successful completion
means the actions finished; exceptional completion preserves a cleanup failure.

```java
context.closeAsync().whenCompleteAsync((ignored, failure) -> {
    if (failure != null) {
        applicationLogger.error("Server cleanup failed", failure);
    }
}, applicationExecutor);
```

The application owns `applicationExecutor` and keeps it available for the callback.
Keep inline completion callbacks short; use an application executor for slow work.

`close()` preserves the synchronous API. Outside framework workers it waits up to
`server.shutdown.timeout.millis`, default **5000**, using a monotonic deadline.
An expired wait throws `ShutdownTimeoutException`. The future remains observable
and cleanup continues. Other context cleanup failures are logged, preserving the
previous tolerant `close()` contract; `closeAsync()` exposes those failures.

Calls from framework I/O, business and detached-worker threads request cleanup
without waiting. Session close listeners, cleanup callbacks and initialization factories may also
reenter their owning context safely.
No context monitor is held while waiting for a callback or calling a close hook.
These rules avoid both self-waits and cross-executor shutdown cycles.

## Cleanup Sequence

The context registers named resource cleanup actions as assembly progresses:

1. Stop listeners and close their channels.
2. Stop detached blocking operations.
3. Close the Game and its sessions.
4. Close the session registry and its scheduler.
5. Stop metric sampling and detach metric resource references.
6. Close shared session executors.
7. Terminate network event loops.

`ShutdownSequence` invokes each action on its own virtual thread. It normally waits
for an action before beginning the next one, within one shared observation budget.
If an action stalls, remaining actions are still initiated. The aggregate future
tracks their actual outcomes. A stalled application hook therefore cannot prevent
network cleanup from being requested. Listener cleanup attempts every started
listener and retains failures instead of stopping after the first one.

`ShutdownRegistry` accepts each returned resource by identity and closes it at most
once. A close request never waits for an initialization factory to return. Resources
already allocated can stop while that factory is blocked; any resource returned
later is registered and immediately closed. Completion also waits for initialization
to finish publishing resources. A permanently blocked factory therefore keeps the
completion pending, while synchronous callers still have a bounded wait.

The registry and sequence only coordinate ownership and completion; it does not depend on
Game, Session, HTTP or a particular application container. `ShutdownSupport`
supplies bounded waiting and execution-context rules shared by resource owners.

## Executor and Payload Ownership

`NetworkBootstrap`, `NettyEventDispatcher`, `ShardedEventDispatcher` and `BlockingTaskExecutor` expose
`closeAsync()` through `AsyncCloseable`. Their futures observe actual termination.
External synchronous waits default to five seconds; a standalone network owner
or dispatcher can select another positive interval with its constructor.

The blocking executor's synchronous wait uses `business.blocking.shutdown.millis`.
A positive expired wait throws `ShutdownTimeoutException`; zero requests shutdown
only. Its asynchronous signal completes after the executor has actually terminated,
with no running task or retained task reservation. Cancelling one returned observation
does not cancel this signal. The context registers this actual completion with its
ownership registry, so an uncooperative worker keeps the context `STOPPING` and the
aggregate cleanup pending while later cleanup stages can still run.

Queued deliveries are cancelled and released. Running callbacks retain their
native payload and byte reservation until they actually exit. Netty's graceful
shutdown timeout does not forcibly stop a running Java callback. A waiting timeout
never means that its resource credits can be reused early. Releasing the callback
later completes cleanup normally; an application cleanup failure remains a failure.

The framework does not use `Thread.stop()` or report abandoned execution as clean
termination. An uncooperative callback can remain alive after a wait expires.
Use detached blocking operations and external dependency deadlines as described
in [slow business operations](SLOW_BUSINESS.md).

## Lifecycle and Readiness

`ServerLifecycle` publishes `CREATED`, `INITIALIZED`, `STARTING`, `RUNNING`,
`STOPPING`, `TERMINATED` or `FAILED`. A late startup completion cannot restore
`RUNNING` after shutdown has begun. Readiness requires `RUNNING` and successful
named resource checks. Initializing a context alone does not publish readiness.

`ListenerRegistry` records the complete selected set before bind begins and samples
`Server.isListening()` without taking the startup monitor. All selected listeners
must be active; disabled listeners are excluded. An unexpectedly closed listening
channel withdraws readiness even while the lifecycle remains `RUNNING` and the
event-loop groups remain open. Custom servers must implement this fast status method;
the interface default is unavailable. The registry keeps startup selection separate
from the HTTP health presenter and avoids a dependency cycle in the component graph.

The HTTP presenter consumes `ServerHealth` snapshots rather than inspecting a
context or casting its Game implementation. The composition root supplies checks
through public resource contracts. Custom dispatchers implement
`EventDispatcher.isHealthy(long)`; the default interface result is unavailable.
The Netty implementation checks closed state and the monotonic age of its oldest
queued or running event without adding heartbeat events to business queues.

See [monitoring](MONITORING.md) for the response schema and counter definitions.
The process-wide dispatcher and sampler remain terminal after context shutdown;
start a new JVM for a new application lifecycle.
