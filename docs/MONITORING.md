# Monitoring Endpoints

Zea-tcp provides comprehensive monitoring endpoints for server health checks and metrics collection. These endpoints are accessible via the HTTP server (default port 8081).

## Health Check Endpoints

### Basic Health Check
**Endpoint:** `GET /health`

Returns simple health status indicating the server is running.

**Response Example:**
```json
{
  "status": "OK",
  "message": "Server is running",
  "timestamp": 1729319400000
}
```

### Liveness Probe
**Endpoint:** `GET /health/live`

Kubernetes-compatible liveness probe that checks if the server is alive and responsive.

**Use Case:** Container orchestrators (Kubernetes, Docker) use this to determine if the container should be restarted.

**Response Example:**
```json
{
  "status": "UP",
  "message": "Server is alive",
  "timestamp": 1729319400000
}
```

**Status Codes:**
- `200 OK` - Server is alive
- `503 Service Unavailable` - Server is not responsive

### Readiness Probe
**Endpoint:** `GET /health/ready`

Kubernetes-compatible readiness probe that checks if the server is ready to accept traffic.

**Use Case:** Load balancers use this to determine if traffic should be routed to this instance.

**Readiness Criteria:**
- Memory usage < 95%
- Error rate < 50% (when message count > 100)

**Response Example:**
```json
{
  "status": "UP",
  "message": "Server is ready",
  "timestamp": 1729319400000
}
```

**Status Codes:**
- `200 OK` - Server is ready
- `503 Service Unavailable` - Server is not ready (high memory, high error rate)

## Metrics Endpoints

### All Metrics
**Endpoint:** `GET /metrics`

Returns comprehensive metrics in JSON format.

**Response Example:**
```json
{
  "server": {
    "uptime_seconds": 3600,
    "uptime_formatted": "1h 0m 0s"
  },
  "connections": {
    "total": 1500,
    "active": 42,
    "channels": 42
  },
  "traffic": {
    "messages_received": 50000,
    "messages_sent": 50000,
    "bytes_received": 5000000,
    "bytes_sent": 5000000,
    "bytes_received_mb": 4,
    "bytes_sent_mb": 4
  },
  "system": {
    "memory_used_mb": 512,
    "memory_max_mb": 2048,
    "memory_usage_percent": 25,
    "thread_count": 32,
    "cpu_load": 0.25
  },
  "errors": {
    "total": 5
  },
  "timestamp": 1729319400000
}
```

### Connection Metrics
**Endpoint:** `GET /metrics/connections`

Returns only connection-related metrics.

**Response Example:**
```json
{
  "total_connections": 1500,
  "active_connections": 42,
  "active_channels": 42,
  "timestamp": 1729319400000
}
```

### Traffic Metrics
**Endpoint:** `GET /metrics/traffic`

Returns only traffic-related metrics.

**Response Example:**
```json
{
  "messages_received": 50000,
  "messages_sent": 50000,
  "bytes_received": 5000000,
  "bytes_sent": 5000000,
  "bytes_received_mb": 4,
  "bytes_sent_mb": 4,
  "timestamp": 1729319400000
}
```

### System Metrics
**Endpoint:** `GET /metrics/system`

Returns only system-related metrics (CPU, memory, threads).

**Response Example:**
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

### Prometheus Metrics
**Endpoint:** `GET /metrics/prometheus`

Returns metrics in Prometheus exposition format for scraping.

**Content-Type:** `text/plain`

**Response Example:**
```
# HELP server_uptime_seconds Server uptime in seconds
# TYPE server_uptime_seconds counter
server_uptime_seconds 3600

# HELP connections_total Total number of connections
# TYPE connections_total counter
connections_total 1500

# HELP connections_active Current active connections
# TYPE connections_active gauge
connections_active 42

# HELP messages_received_total Total messages received
# TYPE messages_received_total counter
messages_received_total 50000

# HELP messages_sent_total Total messages sent
# TYPE messages_sent_total counter
messages_sent_total 50000

# HELP bytes_received_total Total bytes received
# TYPE bytes_received_total counter
bytes_received_total 5000000

# HELP bytes_sent_total Total bytes sent
# TYPE bytes_sent_total counter
bytes_sent_total 5000000

# HELP memory_used_bytes Memory used in bytes
# TYPE memory_used_bytes gauge
memory_used_bytes 536870912

# HELP memory_max_bytes Maximum memory in bytes
# TYPE memory_max_bytes gauge
memory_max_bytes 2147483648

# HELP threads_current Current thread count
# TYPE threads_current gauge
threads_current 32

# HELP cpu_load System CPU load
# TYPE cpu_load gauge
cpu_load 0.25

# HELP errors_total Total errors
# TYPE errors_total counter
errors_total 5
```

## Integration Examples

### Kubernetes Health Checks

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: zea-tcp-server
spec:
  containers:
  - name: zea-tcp
    image: zea-tcp:latest
    ports:
    - containerPort: 8081
    livenessProbe:
      httpGet:
        path: /health/live
        port: 8081
      initialDelaySeconds: 30
      periodSeconds: 10
      timeoutSeconds: 5
      failureThreshold: 3
    readinessProbe:
      httpGet:
        path: /health/ready
        port: 8081
      initialDelaySeconds: 10
      periodSeconds: 5
      timeoutSeconds: 3
      failureThreshold: 2
```

### Prometheus Scraping

```yaml
scrape_configs:
  - job_name: 'zea-tcp'
    static_configs:
      - targets: ['localhost:8081']
    metrics_path: '/metrics/prometheus'
    scrape_interval: 15s
```

### Load Balancer Health Check

Configure your load balancer (Nginx, HAProxy, etc.) to use `/health/ready` for backend health checks:

**Nginx Example:**
```nginx
upstream zea_tcp_backend {
    server 10.0.1.10:8081 max_fails=3 fail_timeout=30s;
    server 10.0.1.11:8081 max_fails=3 fail_timeout=30s;
}

server {
    location / {
        proxy_pass http://zea_tcp_backend;

        # Health check
        health_check uri=/health/ready interval=5s
                     fails=2 passes=2;
    }
}
```

**HAProxy Example:**
```
backend zea_tcp_backend
    mode http
    option httpchk GET /health/ready
    http-check expect status 200

    server server1 10.0.1.10:8081 check inter 5s fall 2 rise 2
    server server2 10.0.1.11:8081 check inter 5s fall 2 rise 2
```

## Metrics Collection

### ServerMetrics Singleton

All metrics are collected via the `ServerMetrics` singleton class:

```java
// Record connection
ServerMetrics.getInstance().recordConnection();

// Record disconnection
ServerMetrics.getInstance().recordDisconnection();

// Record message received
ServerMetrics.getInstance().recordMessageReceived();

// Record bytes received
ServerMetrics.getInstance().recordBytesReceived(1024);

// Record error
ServerMetrics.getInstance().recordError();
```

### Thread Safety

All metrics counters use `AtomicLong` for lock-free thread-safe updates from multiple Netty event loop threads.

### System Metrics

System metrics are obtained from JMX MXBeans:
- **Memory:** `MemoryMXBean` (heap usage)
- **Threads:** `ThreadMXBean` (thread count)
- **CPU:** `OperatingSystemMXBean` (system CPU load)

## Testing Endpoints

You can test the monitoring endpoints using curl:

```bash
# Basic health check (both work)
curl http://localhost:8081/health
curl http://localhost:8081/health/

# Liveness probe (both work)
curl http://localhost:8081/health/live
curl http://localhost:8081/health/live/

# Readiness probe (both work)
curl http://localhost:8081/health/ready
curl http://localhost:8081/health/ready/

# All metrics (both work)
curl http://localhost:8081/metrics
curl http://localhost:8081/metrics/

# Connection metrics only
curl http://localhost:8081/metrics/connections

# Traffic metrics only
curl http://localhost:8081/metrics/traffic

# System metrics only
curl http://localhost:8081/metrics/system

# Prometheus format
curl http://localhost:8081/metrics/prometheus
```

**Note:** All endpoints support both with and without trailing slash (e.g., `/metrics` and `/metrics/` both work).

## Implementation Details

### Handler Classes

- **HealthCheckHandler** (`com.akakata.server.http.HealthCheckHandler`)
  - Handles `/health`, `/health/live`, `/health/ready` endpoints
  - Extends `AbstractHttpHandler` for common HTTP response methods

- **MetricsHandler** (`com.akakata.server.http.MetricsHandler`)
  - Handles `/metrics/*` endpoints
  - Supports JSON and Prometheus formats
  - Extends `AbstractHttpHandler`

### Routing

Endpoints are routed in `HttpRequestHandler.channelRead0()`:

```java
if (baseUri.equals("/health")) {
    healthCheckHandler.handleHealthCheck(ctx, request);
    return;
}

if (baseUri.equals("/metrics")) {
    metricsHandler.handleMetrics(ctx, request);
    return;
}
```

### Dependency Injection

Handlers are provided via Dagger 2 in `ServerModule`:

```java
@Provides
@Singleton
static HealthCheckHandler provideHealthCheckHandler() {
    return new HealthCheckHandler();
}

@Provides
@Singleton
static MetricsHandler provideMetricsHandler() {
    return new MetricsHandler();
}
```

## Configuration

HTTP server port can be configured in `conf.properties`:

```properties
# HTTP server port (monitoring endpoints)
http.port=8081

# Enable/disable HTTP server
server.http.enabled=true
```

## Best Practices

1. **Production Monitoring**: Use `/metrics/prometheus` with Prometheus + Grafana for visualization
2. **Health Checks**: Configure both liveness and readiness probes in Kubernetes
3. **Load Balancers**: Use `/health/ready` for backend health checks
4. **Alerting**: Set up alerts for high error rates, memory usage, and connection counts
5. **Regular Polling**: Poll metrics every 5-15 seconds for real-time monitoring

## Future Enhancements

Potential improvements for monitoring:

- [ ] Request rate limiting metrics
- [ ] Per-protocol connection breakdown
- [ ] Latency percentiles (p50, p95, p99)
- [ ] Custom business metrics
- [ ] Metrics retention and historical data
- [ ] OpenTelemetry integration
