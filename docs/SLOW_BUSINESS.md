# Slow Business Operations

This guide describes the bounded offloading helper introduced in 0.8.2.
See [event ordering](EVENT_LOOP.md#ordering-and-state-ownership) and
[configuration](CONFIGURATION.md) for the existing Session and Game queues.

## Callback Contract

A callback on a Session shard blocks every session assigned to that shard until
it returns. A Game callback blocks all shared Game events and outbound actions.
Login verification, protocol setup, connection callbacks and HTTP routing run on
their caller or Netty I/O thread; they are not automatically moved to the Game.

Callbacks must perform short, bounded state transitions. Never call `get()`,
`join()`, `sleep()`, blocking database/HTTP clients or long CPU loops on an ordered
executor or I/O thread. In particular, never wait for work submitted to the same
executor. Native buffers are borrowed only for the synchronous callback lifetime.
Existing dispatcher timing percentiles reveal callback stalls, but there is no
automatic callback interruption or safe rollback of arbitrary running game code.

Copy the smallest required input into detached immutable application data, admit
slow work to a bounded executor and return promptly. For CPU-heavy work, create a
separately bounded application pool appropriate to that workload; do not consume
all blocking-I/O workers with an unlimited computation.

## Bounded Blocking Executor

The context exposes `getBean(BlockingTaskExecutor.class)`. Its threads start lazily.

```properties
business.blocking.threads=2
business.blocking.queue.capacity=256
business.blocking.queue.bytes=67108864
business.blocking.shutdown.millis=5000
```

`submit(estimatedBytes, Callable<T>)` bounds running plus queued task counts and
caller-declared retained bytes. The estimate must include captured inputs and
maximum working/result storage while the operation runs. Oversized, saturated
and closed submissions return an exceptionally completed future containing
`RejectedExecutionException`. No rejected operation runs inline on its caller.
There is no overflow queue or automatic retry.

Use operation-specific database query, connection and HTTP deadlines. A future
`orTimeout()` only bounds waiting for the result; it cannot force an external
library to stop. Future cancellation requests interruption, and byte/task charges
remain reserved until running work actually exits. Cancelling queued work removes
it and returns its reservation without invoking its body. Failed operations also
return reservations after exiting.

Workers must not mutate Session, Game or application state owned by those queues.
They must not capture borrowed ByteBuf, MessageBuffer or mutable event sources.
This helper does not retain/release application native buffers. Copy them before
submission. Future continuations may execute on the worker, submitting caller or
cancelling caller; a continuation only posts a result event and handles rejection.

## Returning Results to Ordered State

This fragment assumes `player`, `operationId`, `copiedAccountId` and
`loadDetachedProfile(...)` come from an application's short Game callback:

```java
BlockingTaskExecutor workers = context.getBean(BlockingTaskExecutor.class);
Game game = context.getBean(Game.class);

workers.submit(4096, () -> loadDetachedProfile(copiedAccountId))
        .whenComplete((profile, failure) -> {
            if (failure != null) {
                // Route an explicit failure result if the operation is still relevant.
                // Do not retry indefinitely or mutate game state on this thread.
                return;
            }
            Map<String, Object> result = Map.of(
                    "player", player,
                    "operationId", operationId,
                    "profile", profile);
            if (!game.send(Events.event(result, PROFILE_LOADED))) {
                // Admission failed: terminate or reject this application operation.
                // Release any application-owned result resources here.
            }
        });
```

The example uses a Map to expose nested payload storage to the normal estimator.
`profile` must be detached immutable data; provide a conservative
`Event.getPayloadSize()` override if using a large custom result object. Error
results also need a bounded estimate and explicit admission handling. Do not throw
inside a fire-and-forget completion and assume the framework will observe it.

Register a `PROFILE_LOADED` handler on the Game dispatcher. Inside that handler:

1. Confirm the original session instance is still open and belongs to this Game.
   Compare the session instance, not only an account ID, to reject a result for a
   disconnected or replaced connection.
2. Compare the operation ID with the Game-owned current operation state. Remove
   operation state on disconnect, cancellation or completion.
3. Apply the detached result and submit any response through the existing explicit
   Game send methods.

For Session-owned state, post through the original `Session.onEvent()` and perform
these checks in a Session handler instead. A closed session rejects a late result.
Native result references, if an application introduces them, still require explicit
ownership handling across result publication and rejection.

Two independent operations may complete in reverse order. Requeueing serializes
result handling in admission order, not in original request order. Choose an
application policy deliberately:

| Policy | Required Handling |
| --- | --- |
| Latest request wins | Ignore results whose operation ID is no longer current |
| Strict dependent sequence | Admit the next operation only after the previous result is applied |
| Independent operations | Validate identity and apply each relevant result independently |

Avoid an unbounded map of out-of-order results or an unbounded chain of pending
futures. The normal event queues continue to guarantee ordered callbacks; the
worker pool does not weaken or replace their event admission.

## Shutdown and Monitoring

Context shutdown stops listeners, closes blocking workers, then closes the Game,
session registry, dispatchers and network groups. Worker closure cancels queued
futures and interrupts running operations. External close waits for at most
`business.blocking.shutdown.millis`; an owned worker never waits for itself.
Uncooperative work may continue after that deadline and retains its reservation
until it returns. Workers are daemon threads, so such work cannot prevent JVM exit.
Set external dependency timeouts so operations normally finish before shutdown.

`/metrics/flow-control` includes a `blocking` group with `threads`,
`queue_capacity`, `byte_budget`, `pending_tasks`, `pending_bytes` and
`active_workers`. Prometheus emits `zea_flow_blocking_<field>` gauges.
Inspect these alongside Session/Game queue waits and callback durations. A low
ordered-queue depth does not establish that external operations are fast or that
there is no running work.

Regression tests cover queue/byte rejection, queued and running cancellation,
bounded shutdown with an interruption-resistant operation, responsive ordered
Game events during slow work, and rejection of a late result for a closed Session.
