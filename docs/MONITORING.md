# Monitoring Endpoints

This guide describes lifecycle and instrumentation in
[Zea TCP Transport 0.8.5](../CHANGELOG.md#085).
The bundled HTTP listener binds to loopback
on port 8081. Enable `server.http.enabled` and use the configured `http.port`;
the HTTP-only example uses 18081. See [configuration](CONFIGURATION.md).

Responses close the HTTP connection. JSON uses `application/json; charset=UTF-8`;
Prometheus uses `text/plain; charset=UTF-8`. These routes have no built-in request
authentication. Health routes accept query strings and a single trailing slash.
For the exact root `/metrics`, continue using a query-free URL.

## Health Checks

| Endpoint | Availability Policy | HTTP Status |
| --- | --- | --- |
| `GET /health` | Application readiness | 200 when ready, otherwise 503 |
| `GET /health/live` | Initialized, starting, running or draining lifecycle | 200 when live, otherwise 503 |
| `GET /health/ready` | Running lifecycle and all named checks | 200 when ready, otherwise 503 |

Both successful and unavailable health responses contain JSON:

```json
{
  "status": "UP",
  "phase": "RUNNING",
  "checks": {
    "game": true,
    "session_dispatchers": true,
    "session_registry": true,
    "blocking_workers": true,
    "network": true,
    "listeners": true,
    "heap": true
  },
  "timestamp": 1791820800000
}
```

The response status becomes `DOWN` and HTTP 503 when its availability policy fails.
An unbound `HealthCheckHandler()` reports unavailable; the default Dagger graph
injects `ServerHealth`. `DefaultServerHealth` combines a `ServerLifecycle` with
fast, nonblocking named checks. HTTP presentation owns no application resource.

Readiness requires completed listener startup. It is withdrawn immediately on
shutdown, even if cleanup is still running. Closing a Game, registry, worker pool
or I/O owner withdraws readiness. The default checks also fail when any Game or
session event has remained queued or running for
`health.maximum.event.delay.millis`, default 5000, or used heap reaches 95% of
maximum heap. A throwing check fails closed. Custom dispatchers implement
`EventDispatcher.isHealthy(long)`; its default returns false.

The `listeners` check requires every selected listener's actual bound channel to be
active. Disabled listeners are excluded. Closing a listening channel withdraws
readiness even if its event-loop groups remain open; `network` reports the separate
group lifecycle. These checks do not acquire the server manager's blocking startup
monitor. Custom servers implement the nonblocking `Server.isListening()` contract.

Liveness no longer waits for a whole second of uptime. Draining remains live while
readiness is down. A failed or terminated lifecycle is not live. No automatic
SQL, cache-server or remote-service check is inferred. Lifetime error counters do
not determine readiness. See [lifecycle](LIFECYCLE.md) for completion and timeout rules.

## Metrics Availability and Units

Default pipelines now populate `ServerMetrics` automatically:

| Counter | Definition |
| --- | --- |
| Connections | Observed TCP, HTTP and WebSocket client sockets, excluding listener channels; duplicate activation is ignored |
| Active connections | Observed sockets whose close future has not completed |
| Received bytes | Raw bytes before transport codecs, including framing and handshakes |
| Sent bytes | Encoded buffers and file-region bytes whose transport promises complete successfully |
| Received messages | Decoded `SESSION_MESSAGE` events before Session admission; login/control opcodes and malformed frames are excluded |
| Sent messages | `NetworkEvent` writes whose sender promise completes successfully; login acknowledgements are excluded |
| Errors | Failed transport-write callbacks, recorded inbound pipeline failures and business callback failures |

A received message can subsequently be rejected by admission. A successful send
means transport completion, not acknowledgement by the peer application. One
application message can produce several encoded writes; error callbacks are not
unique failed-message counts and must not be used as a message failure probability.
Admission denials and connection/pause deadlines retain their separate flow counters.

`TransportMetricsHandler` survives protocol replacement using the
`ConnectionScopedHandler` contract. Application message instrumentation is installed
after each event decoder. Adding another handler that records the same socket or
messages in the shared collector would double-count them. Use an independent
`ServerMetrics` collector for separate application measurements.

- All `timestamp` fields are epoch milliseconds at response generation.
- Uptime starts when the process-wide collector is constructed.
- `_mb` fields are truncated MiB values, using division by 1024 squared.
- `cpu_load` is a system CPU fraction rounded independently of the default locale;
  negative readings mean unavailable.
- `thread_count` counts platform threads, excluding virtual threads.
- Registered-channel readings use `AbstractNettyServer.ALL_CHANNELS` and can include
  listener channels. They differ from observed client-socket counts and from
  `FlowControlMetrics` admission counts, which exclude rejected sockets.

The following JSON examples are illustrative; runtime values vary.

## Combined Metrics

`GET /metrics` returns the legacy groups shown below plus a live `flow_control`
group described in [Flow-Control Metrics](#flow-control-metrics).

```json
{
  "server": {
    "uptime_seconds": 3600,
    "uptime_formatted": "1h 0m 0s"
  },
  "connections": {
    "total": 0,
    "active": 0,
    "channels": 3
  },
  "traffic": {
    "messages_received": 0,
    "messages_sent": 0,
    "bytes_received": 0,
    "bytes_sent": 0,
    "bytes_received_mb": 0,
    "bytes_sent_mb": 0
  },
  "system": {
    "memory_used_mb": 512,
    "memory_max_mb": 2048,
    "memory_usage_percent": 25,
    "thread_count": 32,
    "cpu_load": 0.25
  },
  "errors": {
    "total": 0
  },
  "timestamp": 1729319400000
}
```

`uptime_formatted` uses seconds, minutes and hours, adding days when appropriate.
`memory_usage_percent` is the truncated integer percentage calculated from the
heap MiB getters. Multiple getter calls do not form an atomic snapshot.

## Connection Metrics

`GET /metrics/connections` uses different field names from the combined group:

```json
{
  "total_connections": 0,
  "active_connections": 0,
  "active_channels": 3,
  "timestamp": 1729319400000
}
```

`active_connections` is the observed client-socket balance.
`active_channels` is the static channel group's size. Do not substitute one for
the other when creating dashboards.

## Traffic Metrics

`GET /metrics/traffic` returns cumulative recorded counters:

```json
{
  "messages_received": 0,
  "messages_sent": 0,
  "bytes_received": 0,
  "bytes_sent": 0,
  "bytes_received_mb": 0,
  "bytes_sent_mb": 0,
  "timestamp": 1729319400000
}
```

The counters have no per-protocol labels or embedded rate calculations.
`ServerMetrics.reset()` can reset them during the process lifetime.

## System Metrics

`GET /metrics/system` returns runtime readings plus collector uptime:

```json
{
  "memory_used_mb": 512,
  "memory_max_mb": 2048,
  "memory_usage_percent": 25,
  "thread_count": 32,
  "cpu_load": 0.25,
  "uptime_seconds": 3600,
  "timestamp": 1729319400000
}
```

Memory readings describe JVM heap, excluding Netty direct memory and other native
allocations. CPU collection requires the JVM's `com.sun.management` operating
system management bean.

## Dispatcher Queue Metrics

`GET /metrics/event-dispatcher` returns sampled shared-dispatcher data. This is an
illustrative two-shard sample:

```json
{
  "total_queue_size": 4,
  "per_shard_queue_sizes": [1, 3],
  "shard_count": 2,
  "max_shard_queue_size": 3,
  "min_shard_queue_size": 1,
  "average_shard_queue_size": 2,
  "last_update_timestamp": 1729319400000,
  "is_started": true,
  "timestamp": 1729319400500
}
```

| Field | Meaning |
| --- | --- |
| `total_queue_size` | Cached aggregate shared queue depth |
| `per_shard_queue_sizes` | Cached queue depths, indexed by shard |
| `shard_count` | Shared dispatcher shard count from the current process |
| `max_shard_queue_size` | Maximum cached shard depth |
| `min_shard_queue_size` | Minimum cached shard depth |
| `average_shard_queue_size` | Truncated integer average |
| `last_update_timestamp` | Last successful sampling time; zero before sampling |
| `is_started` | Whether positive-interval sampling was started; remains true after stop |
| `timestamp` | Response-generation time |

The default sampling interval is 30 seconds. Configure
`metrics.event.interval.seconds=5` for five-second sampling, or a nonpositive value
to disable it. Before sampling, the cached total is zero and the per-shard array
is empty even though `shard_count` can be positive.

These values count pending Netty tasks and exclude the application Game queue and
running callbacks. Cancelled task shells may remain counted until their worker
reaches them, although their event and native payload references are already cleared. Total and
per-shard depths are read separately, so their sums need not match during concurrent
publication. Use `last_update_timestamp` to assess freshness. A growing maximum
can identify a hot shard; a zero queue does not prove that all handlers have finished.

Game sends provide per-operation `DeliveryReport` recipient admission counts.
A send report remains distinct from successful socket writes or client acknowledgment.
Live flow-control snapshots supplement the older sampled session queue endpoint.

The bundled HTTP listener explicitly binds to `127.0.0.1`. Configure
`http.bind.host` for a private monitoring network when needed; the endpoints have
no built-in authentication. See [security configuration](CORE_COMPONENTS.md).

## Flow-Control Metrics

`GET /metrics/flow-control` returns `session`, `game`, `transport`, `outbound` and `blocking` groups. These
are live readings independent of `metrics.event.interval.seconds`; the combined
`/metrics` response includes the same groups under `flow_control`.

Each session shard and the current Game exposes:

| Field | Meaning |
| --- | --- |
| `queued` / `capacity` | Pending deliveries excluding the running callback / configured task budget |
| `pendingBytes` / `byteBudget` | Estimated admitted payload bytes including running work / configured byte budget |
| `accepted`, `completed`, `cancelled`, `rejected` | Cumulative admission, execution, pre-execution cancellation and saturation outcomes |
| `waitP95Micros`, `waitP99Micros` | Queue wait percentile bucket upper bounds in microseconds |
| `executionP95Micros`, `executionP99Micros` | Callback duration percentile bucket upper bounds in microseconds |

Histograms use bounded power-of-two microsecond buckets. Values are approximate
upper bounds, not exact request latency percentiles; no samples returns zero.
Completed counts include executed callbacks whose application handler failed.
Closed-scope submissions are excluded from saturation rejections. Queue bytes
are conservative storage charges, not exact JVM memory readings.

Transport fields `active_connections`, `paused_channels`, `pending_writes`,
`pending_socket_bytes`, `pending_payload_bytes` and `pending_http_file_transfers`
are live gauges. `connection_rejections`, `login_timeouts`, `idle_timeouts`,
`http_timeouts`, `read_pauses`, `pause_timeouts`, `write_rejections`, `write_timeouts`,
`encoded_frames`, `http_file_transfer_rejections`, `http_file_transfer_timeouts`
and `encoding_reuses` are cumulative counters. Active connections count admitted
TCP/WebSocket/HTTP sockets including unauthenticated clients. HTTP additionally
has its own connection sublimit and absolute request deadline. Encoded byte gauges remain charged until the write promise settles.
The `outbound` group reports the context's `byte_budget` and `pending_bytes`,
including source estimates and encoded buffers. The `blocking` group reports
`threads`, `queue_capacity`, `byte_budget`, `pending_tasks`, `pending_bytes` and
`active_workers`, `closed` and `terminated`; cancelled running operations retain their charges until exit.
The final two are 0/1 flags distinguishing a shutdown request from actual executor
termination. A worker that ignores interruption keeps `terminated` at zero and the
context's actual cleanup future pending, even after a synchronous wait expires.
Prometheus exposes these scalar values as `zea_flow_outbound_<field>` and
`zea_flow_blocking_<field>` gauges. Source and encoded charges coexist while both
resources are retained. `outbound` and `blocking` are null outside an initialized
context; a custom non-Netty Game dispatcher has no default Game timing snapshot.

File-transfer admission and deadlines are independent of encoded byte gauges.
`pending_http_file_transfers` includes opening files and responses awaiting either
their file write or final content write. Rejections happen before opening another
file. Transfer timeouts close the connection and its owned handles. Prometheus
exports `zea_flow_pending_http_file_transfers` as a gauge and
`zea_flow_http_file_transfer_rejections` / `zea_flow_http_file_transfer_timeouts`
as cumulative counters. See [configuration](CONFIGURATION.md#shutdown-health-and-static-resources)
for quotas and completion deadlines.

Flow-control counters are instrumented by the default pipelines and require no application
`ServerMetrics.record*()` calls. They supplement the automatically populated traffic counters.
`game` is null when no context Game is registered. Readings across queues are not
an atomic process snapshot.

## Prometheus Output

`GET /metrics/prometheus` emits the following exact names and types:

| Name | Emitted Type | Value |
| --- | --- | --- |
| `server_uptime_seconds` | counter | Collector uptime |
| `connections_total` | counter | Recorded total connections |
| `connections_active` | gauge | Recorded connection balance |
| `channels_active` | gauge | Registered channel-group size |
| `messages_received_total` | counter | Recorded received messages |
| `messages_sent_total` | counter | Recorded sent messages |
| `bytes_received_total` | counter | Recorded received bytes |
| `bytes_sent_total` | counter | Recorded sent bytes |
| `memory_used_bytes` | gauge | Truncated heap MiB converted back to bytes |
| `memory_max_bytes` | gauge | Truncated maximum heap MiB converted back to bytes |
| `threads_current` | gauge | Platform-thread count |
| `cpu_load` | gauge | Rounded system CPU fraction |
| `errors_total` | counter | Recorded errors |

Example excerpt:

```text
# HELP connections_total Total number of connections
# TYPE connections_total counter
connections_total 0

# HELP channels_active Current active channels
# TYPE channels_active gauge
channels_active 3
```

Flow transport fields also appear as `zea_flow_<field>` with their gauge/counter
types. Executors use `zea_executor_queued_tasks`, `pending_bytes`, `task_capacity`,
`byte_budget`, `accepted`, `completed`, `cancelled`, `rejected`, `wait_p95_micros`,
`wait_p99_micros`, `execution_p95_micros` and `execution_p99_micros`, each with the
`zea_executor_` prefix and labels `scope="session"|"game"` and `shard="<index>"`.
Admission/execution outcomes are counters; budgets, occupancy and percentile
readings are gauges. There are no per-player series or exported histogram buckets.
Legacy counters can decrease when explicitly reset; flow counters are cumulative.

Example Prometheus scrape configuration for a local server:

```yaml
scrape_configs:
  - job_name: zea-tcp
    metrics_path: /metrics/prometheus
    static_configs:
      - targets: ["localhost:8081"]
```

Use a target reachable from the Prometheus process; `localhost` refers to that
process's host or container.

## Probe Configuration

An example Kubernetes container probe fragment for the HTTP listener on 8081:

```yaml
livenessProbe:
  httpGet:
    path: /health/live
    port: 8081
  initialDelaySeconds: 10
  periodSeconds: 10
readinessProbe:
  httpGet:
    path: /health/ready
    port: 8081
  initialDelaySeconds: 5
  periodSeconds: 5
```

Match the port to `http.port`. These probes expose the local checks described above;
supply application-specific `ServerHealth` checks when your deployment requires dependency health.

## Inspect Endpoints

Start a repository example using the [README instructions](../README.md#run-the-repository-example),
then issue:

```bash
curl -i http://localhost:8081/health
curl -i http://localhost:8081/health/live
curl -i http://localhost:8081/health/ready
curl http://localhost:8081/metrics
curl http://localhost:8081/metrics/connections
curl http://localhost:8081/metrics/traffic
curl http://localhost:8081/metrics/system
curl http://localhost:8081/metrics/event-dispatcher
curl http://localhost:8081/metrics/flow-control
curl http://localhost:8081/metrics/prometheus
```

Substitute 18081 for the HTTP-only example. Health or metrics subpaths that match
no handler branch return HTTP 404. Handler failures can return HTTP 500 with a
plain-text error body.

## Source References

- [HttpRequestHandler](../src/main/java/com/akakata/handlers/HttpRequestHandler.java)
- [HealthCheckHandler](../src/main/java/com/akakata/server/http/HealthCheckHandler.java)
- [MetricsHandler](../src/main/java/com/akakata/server/http/MetricsHandler.java)
- [Event loops and lifecycle](EVENT_LOOP.md)
