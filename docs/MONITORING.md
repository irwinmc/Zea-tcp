# Monitoring Endpoints

Zea TCP Transport 0.7.8 serves health and metrics endpoints through its HTTP
listener, whose bundled port is 8081. Enable `server.http.enabled` and use the
configured `http.port`. The HTTP-only repository example uses port 18081 instead.
See the [configuration guide](CONFIGURATION.md).

The following requests use the normal endpoint paths. A single trailing slash is
accepted. Use query-free URLs for `/health` and `/metrics`: although the outer router
normalizes the path, the health and metrics handlers inspect the original URI for
these exact root matches. Sub-endpoints currently use prefix matching.

Successful JSON responses use `application/json; charset=UTF-8`. Prometheus output
uses `text/plain; charset=UTF-8`. Responses close the HTTP connection. These routes
have no built-in authentication, and the default HTTP listener uses plaintext.

## Health Checks

| Endpoint | Successful Body | Failure Behavior |
| --- | --- | --- |
| `GET /health` | Status `OK`, message `Server is running` | Handler failures return an error response |
| `GET /health/live` | Status `UP`, message `Server is alive` | HTTP 503 when the uptime check fails |
| `GET /health/ready` | Status `UP`, message `Server is ready` | HTTP 503 when a resource check fails |

Each successful body contains a Unix epoch timestamp in milliseconds:

```json
{
  "status": "UP",
  "message": "Server is ready",
  "timestamp": 1729319400000
}
```

`/health` responds when the request handler is reachable; it does not inspect all
application components. Liveness requires `ServerMetrics.getUptimeSeconds() > 0`
and can return 503 during the collector's first second.

Readiness fails if:

- Used heap divided by maximum heap, using the integer MiB getters, exceeds 0.95.
- Recorded received messages exceed 100 and cumulative recorded errors divided by
  those messages exceeds 0.5.
- A check throws an exception.

Values exactly at the two thresholds pass. No thread-count threshold, database
check, queue-depth check or minimum connected-player count is enforced. Zero
registered channels does not itself fail readiness. The error ratio is cumulative,
not a rate over a time window. Counters remain zero unless an application adds
instrumentation, so the default error-ratio check usually has no recorded traffic
to evaluate.

An unsuccessful liveness or readiness response is **plain text**, not a JSON
`DOWN` object:

```text
Failure: 503 Service Unavailable
```

## Metrics Availability and Units

Connection, traffic and error counters depend on explicit
`ServerMetrics.record*()` calls. The current default handlers and senders do not
make those calls. Runtime heap, uptime, CPU, thread count and registered-channel
readings are available independently; dispatcher depths are sampled.

- All `timestamp` fields are epoch milliseconds at response generation.
- Uptime starts when the singleton collector is constructed, not at listener bind.
- `_mb` fields are truncated **MiB** values, using division by 1,024 squared.
- `cpu_load` is system CPU utilization as a fraction, rounded to two decimals;
  `0.25` means 25%. A negative value indicates an unavailable reading.
- `thread_count` is the management bean's platform-thread count, excluding virtual
  threads. It does not count concurrent dispatcher callbacks.
- Channel counts come from `AbstractNettyServer.ALL_CHANNELS`. The group includes
  listening channels and explicitly registered TCP client channels; it is not a
  complete HTTP or WebSocket client count.

The JSON examples below are illustrative. Real runtime values vary, and the
examples' zero counters reflect an application without added instrumentation.

## Combined Metrics

`GET /metrics` returns these groups. It does **not** include dispatcher queues.

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

`active_connections` is the explicitly recorded connection balance.
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

These values exclude dedicated game queues and active callback tasks. Total and
per-shard depths are read separately, so their sums need not match during concurrent
publication. Use `last_update_timestamp` to assess freshness. A growing maximum
can identify a hot shard; a zero queue does not prove that all handlers have finished.

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

The output currently has no dispatcher queue metrics, protocol labels, latency
histograms or per-session series. Resetting counters can cause decreases; the
emitted types above reflect the current handler exactly.

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
add application-specific dependency checks if your routing decisions require them.

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
curl http://localhost:8081/metrics/prometheus
```

Substitute 18081 for the HTTP-only example. Health or metrics subpaths that match
no handler branch return HTTP 404. Handler failures can return HTTP 500 with a
plain-text error body.

## Source References

- [HttpRequestHandler](../src/main/java/com/akakata/handlers/HttpRequestHandler.java)
- [HealthCheckHandler](../src/main/java/com/akakata/server/http/HealthCheckHandler.java)
- [MetricsHandler](../src/main/java/com/akakata/server/http/MetricsHandler.java)
- [Collector architecture](METRICS_ARCHITECTURE.md)
