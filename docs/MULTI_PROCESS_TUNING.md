# Several JVMs on a Four-Core, 16 GiB Host

These are starting budgets for a measured deployment, not connection or throughput
guarantees. The remote echo tests run one server at a time on an eight-vCPU host.
They apply fractional CPU quotas to that server and run clients in separate JVMs.
They do not reproduce scheduling, cache, GC or memory interference from eight to
twelve application servers running together on a four-core machine.

## Reserve Host Capacity

Reserve approximately one of the four cores for the OS, networking, dependencies
and traffic variation before assigning application budgets:

| Server JVMs | Example CPU ceiling per JVM | Combined application ceiling |
| --- | --- | --- |
| 8 | 37.5% of one logical CPU | 3 cores |
| 12 | 25% of one logical CPU | 3 cores |

These example quotas are planning values. The current single-server comparison
tests 33% and 50%, so validate 25% and 37.5% with the application's actual handlers
before deployment. Quotas distribute a finite host budget; they do not add CPU.
Use representative peak traffic and retain the headroom needed by MySQL, Redis
and any other applications on the same machine.

Start with one boss thread, one I/O worker and one Session dispatcher shard per
low-CPU JVM. Each Game keeps its separate ordered executor. Compare two workers
and two shards under the same quota before increasing them. More threads can
increase context switches without increasing the available CPU.

CPU quota periods also affect observed latency. The benchmark compares 100 ms
and 20 ms server periods at the same 33% CPU allowance. Treat period tuning as
an operating-system scheduling decision and measure its throughput and tail
latency together. Keep the client envelope unchanged during that comparison.
The current 20 ms probe had lower throughput and higher P99 than the 100 ms
probe, so retain 100 ms as the starting period for this workload. Recheck period
tuning with the real application before changing it.

## Explicit JVM and Queue Budgets

The isolated echo tests use these JVM options:

```text
-Xms64m -Xmx256m -Xss512k
-XX:MaxDirectMemorySize=128m -XX:MaxMetaspaceSize=128m
-XX:ActiveProcessorCount=1
```

They also enforce a 768 MiB total-memory ceiling per server cgroup. Heap, direct
buffers and metaspace are separate allowances; RSS includes code, thread stacks,
JVM/native bookkeeping and shared pages as well. Production business state may
need a larger heap. Measure that requirement before raising the ceiling and
multiply every process budget by the intended process count.
The remote tests use `Nice=19` and `CPUWeight=1` to yield to existing applications;
production service priority requires its own resource plan.

The loopback-only benchmark resource uses:

```properties
bossThreadCount=1
workerThreadCount=1
session.dispatcher.shards=1
session.queue.capacity=4096
session.queue.bytes=16777216
game.queue.capacity=4096
game.queue.bytes=16777216
socket.maximum.pending.writes=1024
socket.maximum.pending.bytes=1048576
socket.maximum.pending.payload.bytes=1048576
socket.total.pending.bytes=67108864
business.blocking.threads=1
business.blocking.queue.capacity=16
business.blocking.queue.bytes=1048576
```

At one Session shard, retained queue/source estimates total at most 97 MiB across
these allowances. They are logical pressure budgets, not a prediction of heap
usage or RSS. Shared payloads can be charged more than once; object graphs,
encoding intermediates, allocator pools and kernel buffers need separate memory
headroom. Increasing Session shards multiplies that per-shard allowance.
The test admits at most 256 sessions and 512 total sockets. Those are explicit
test boundaries, not measured production maxima.

Keep production authentication closed until the application supplies a verifier.
Each process needs its own listener ports and all three required bind-host keys,
including disabled listeners. The echo helper's anonymous login and ephemeral
loopback ports are for isolated testing. Load thread settings from the classpath
resource before constructing `ServerContext`; changing properties afterward does
not reconstruct its network groups.

## Read the Evidence

Warm up before measurement and separate client/server CPU and memory accounting.
Inspect completed and failed replies, exact client P99, server CPU seconds, GC
count/time, RSS/direct memory, and Session/Game queue pressure together. Every
remote workload produces JSON and readable reports with its artifact checksum,
limits, before/after snapshots and cleanup result. Keep failed attempts too.

JFR reports filter CPU/allocation/monitor samples to the server snapshot interval.
Short recordings may contain too few CPU samples to establish a dominant hotspot.
Use unprofiled runs for thread and throughput comparisons. Profiled and unprofiled
rates should not be compared as an isolated implementation change.

If JSON object allocation remains material with representative traffic, measure
codec/serialization changes and a compact protocol before changing APIs. If Game
callbacks dominate CPU or waiting, shorten callbacks and use the existing bounded
blocking executor for external calls. Return detached results to the original
Session or Game and preserve operation order as described in
[slow business operations](SLOW_BUSINESS.md).

The next capacity test needs the actual four-core host and realistic handlers.
Measure one server first, then increase simultaneous processes gradually while
monitoring host headroom. Establish per-process connection and message-rate limits
from that evidence. The current tests do not justify an eight-to-twelve-process
capacity promise.
