# Metrics Architecture

## Architecture Overview

Zea-tcp's metrics system adopts a **modular design**, providing two independent metrics collectors.

```
Metrics System
    ├── ServerMetrics (Server Metrics)
    │   ├── Connection Metrics
    │   ├── Traffic Metrics
    │   ├── System Metrics
    │   └── Error Metrics
    │
    └── EventDispatcherMetrics (Event Dispatcher Metrics)
        └── Queue Status Monitoring
```

## Design Principles

### 1. **Single Responsibility Principle (SRP)**
Each metrics collector is responsible for only one type of metrics:
- `ServerMetrics` → Server-level metrics
- `EventDispatcherMetrics` → Event dispatcher queue metrics

### 2. **Singleton Pattern**
All metrics collectors adopt the singleton pattern:
- `ServerMetrics.getInstance()` - Get server metrics singleton
- `EventDispatcherMetrics.getInstance()` - Get event dispatcher metrics singleton
- Ensures globally unique instances, avoiding duplicate creation

### 3. **Thread Safety**
- `ServerMetrics`: Uses `AtomicLong` to ensure thread safety
- `EventDispatcherMetrics`: Uses `AtomicBoolean`, `AtomicInteger`, `AtomicReference` to ensure thread safety

## Core Components

### 1. ServerMetrics

**Collected Metrics:**

| Category | Metric | Description |
|----------|--------|-------------|
| **Connections** | `totalConnections` | Total connection count |
| | `activeConnections` | Current active connections |
| | `currentChannelCount` | Current channel count |
| **Traffic** | `totalMessagesReceived` | Total messages received |
| | `totalMessagesSent` | Total messages sent |
| | `totalBytesReceived` | Total bytes received |
| | `totalBytesSent` | Total bytes sent |
| **System** | `usedMemoryMB` | Used memory (MB) |
| | `maxMemoryMB` | Maximum memory (MB) |
| | `threadCount` | Thread count |
| | `cpuLoad` | CPU load |
| | `uptimeSeconds` | Uptime (seconds) |
| **Errors** | `totalErrors` | Total error count |

**Usage Example:**
```java
// Direct access to singleton
ServerMetrics serverMetrics = ServerMetrics.getInstance();

// Record metrics
serverMetrics.recordConnection();
serverMetrics.recordDisconnection();
serverMetrics.recordMessageReceived();
serverMetrics.recordBytesReceived(1024);

// Read metrics
long total = serverMetrics.getTotalConnections();
long active = serverMetrics.getActiveConnections();
double cpuLoad = serverMetrics.getCpuLoad();
```

### 2. EventDispatcherMetrics

**Features:**
- Periodically monitors event dispatcher queue status
- Outputs total queue size and per-shard queue sizes
- Helps identify performance bottlenecks and hot shards

**Use Cases:**
- Load testing
- Load balancing verification
- Performance tuning
- Production environment monitoring

**Usage Example:**
```java
// Direct use of singleton
EventDispatcherMetrics metrics = EventDispatcherMetrics.getInstance();
metrics.start(5); // Collect every 5 seconds

// Get metrics data
int totalQueueSize = metrics.getTotalQueueSize();
int[] perShardSizes = metrics.getPerShardQueueSizes();
int maxShardSize = metrics.getMaxShardQueueSize();

// Stop monitoring
metrics.stop();
```

**HTTP Access Example:**
```bash
# Get event dispatcher queue metrics
curl http://localhost:8081/metrics/event-dispatcher

# Response example
{
  "total_queue_size": 1234,
  "per_shard_queue_sizes": [150, 148, 162, 155, 149, 153, 158, 159],
  "shard_count": 8,
  "max_shard_queue_size": 162,
  "min_shard_queue_size": 148,
  "average_shard_queue_size": 154,
  "last_update_timestamp": 1698765432100,
  "is_started": true,
  "timestamp": 1698765432500
}
```

## Integration Methods

### Method 1: Use ServerMetrics in Handler

```java
public class MyHandler {
    private final ServerMetrics metrics = ServerMetrics.getInstance();

    public void channelActive(ChannelHandlerContext ctx) {
        metrics.recordConnection();
    }

    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        metrics.recordMessageReceived();
        if (msg instanceof ByteBuf buf) {
            metrics.recordBytesReceived(buf.readableBytes());
        }
    }
}
```

### Method 2: Configure EventDispatcherMetrics at Startup

```java
public class MyServer {
    public void start() {
        // Start event dispatcher monitoring (optional)
        EventDispatcherMetrics edMetrics = EventDispatcherMetrics.getInstance();
        long interval = configManager.getLong("metrics.event.dispatcher.interval", 0);
        if (interval > 0) {
            edMetrics.start(interval);
        }
    }

    public void shutdown() {
        EventDispatcherMetrics.getInstance().stop();
    }
}
```

## HTTP API Integration

`MetricsHandler` automatically uses `ServerMetrics` and `EventDispatcherMetrics`:

```java
// Inside MetricsHandler
private final ServerMetrics metrics = ServerMetrics.getInstance();

private void handleAllMetrics(ChannelHandlerContext ctx) {
    // Use ServerMetrics to get data and return JSON
}

private void handleEventDispatcherMetrics(ChannelHandlerContext ctx) {
    EventDispatcherMetrics edMetrics = EventDispatcherMetrics.getInstance();
    // Get event dispatcher queue data and return JSON
}
```

**Available HTTP Endpoints:**
- `GET /metrics` - All metrics
- `GET /metrics/connections` - Connection metrics
- `GET /metrics/traffic` - Traffic metrics
- `GET /metrics/system` - System metrics
- `GET /metrics/event-dispatcher` - Event dispatcher queue metrics
- `GET /metrics/prometheus` - Prometheus format

## Extending New Metrics Collectors

### Step 1: Create New Metrics Collector

```java
public class DatabaseMetrics {
    private final AtomicLong queryCount = new AtomicLong(0);
    private final AtomicLong slowQueryCount = new AtomicLong(0);

    public void recordQuery() {
        queryCount.incrementAndGet();
    }

    public void recordSlowQuery() {
        slowQueryCount.incrementAndGet();
    }

    public long getQueryCount() {
        return queryCount.get();
    }

    public long getSlowQueryCount() {
        return slowQueryCount.get();
    }
}
```

### Step 2: Expose Endpoint in MetricsHandler

```java
public class MetricsHandler extends AbstractHttpHandler {
    public void handleMetrics(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();

        if (uri.startsWith("/metrics/database")) {
            handleDatabaseMetrics(ctx);
            return;
        }
        // ... other endpoints ...
    }

    private void handleDatabaseMetrics(ChannelHandlerContext ctx) {
        DatabaseMetrics db = DatabaseMetrics.getInstance();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query_count", db.getQueryCount());
        response.put("slow_query_count", db.getSlowQueryCount());
        response.put("timestamp", System.currentTimeMillis());

        String json = MAPPER.writeValueAsString(response);
        sendHttpResponse(ctx, Unpooled.copiedBuffer(json, CharsetUtil.UTF_8),
                        "application/json; charset=UTF-8");
    }
}
```

## UML Class Diagram

```
┌─────────────────────┐     ┌──────────────────────┐
│   ServerMetrics     │     │EventDispatcherMetrics│
│    (Singleton)      │     │     (Singleton)      │
├─────────────────────┤     ├──────────────────────┤
│ - connections       │     │ - scheduler          │
│ - traffic           │     │ - totalQueueSize     │
│ - system            │     │ - perShardQueueSizes │
│ - errors            │     │ - started            │
├─────────────────────┤     ├──────────────────────┤
│ + getInstance()     │     │ + getInstance()      │
│ + record*()         │     │ + start(interval)    │
│ + get*()            │     │ + stop()             │
│                     │     │ + getTotalQueueSize()│
│                     │     │ + getPerShardSizes() │
└─────────────────────┘     └──────────────────────┘
           ▲                           ▲
           │                           │
           │                           │
           └───────────┬───────────────┘
                       │
                       │ uses
                       ▼
           ┌───────────────────────┐
           │   MetricsHandler      │
           ├───────────────────────┤
           │ - metrics             │
           ├───────────────────────┤
           │ + handleMetrics()     │
           │ + handleAllMetrics()  │
           │ + handleEventDisp...()│
           └───────────────────────┘
```

## Configuration Example

```properties
# conf.properties

# Event dispatcher metrics monitoring interval (seconds)
# Set to 0 or negative to disable
metrics.event.dispatcher.interval=5

# HTTP metrics endpoint port
http.port=8081
```

## Test Usage

```java
public class MetricsTest {
    @Test
    public void testServerMetrics() {
        ServerMetrics metrics = ServerMetrics.getInstance();

        // Record some metrics
        metrics.recordConnection();
        metrics.recordMessageReceived();
        metrics.recordBytesReceived(1024);

        // Verify
        assertEquals(1, metrics.getTotalConnections());
        assertEquals(1, metrics.getTotalMessagesReceived());
        assertEquals(1024, metrics.getTotalBytesReceived());

        // Cleanup
        metrics.reset();
    }

    @Test
    public void testEventDispatcherMetrics() {
        EventDispatcherMetrics metrics = EventDispatcherMetrics.getInstance();

        // Start monitoring
        metrics.start(1);

        // Wait for collection
        Thread.sleep(1500);

        // Verify data has been collected
        assertTrue(metrics.getLastUpdateTimestamp() > 0);
        assertTrue(metrics.getShardCount() > 0);

        // Stop monitoring
        metrics.stop();
    }
}
```

## Best Practices

### 1. Use Singleton Pattern Directly
```java
// Recommended - Use singleton directly
ServerMetrics.getInstance().recordConnection();
EventDispatcherMetrics.getInstance().start(5);
```

### 2. Configure Monitoring at Application Startup
```java
public void startServer() {
    // Start event dispatcher monitoring based on configuration
    long interval = config.getLong("metrics.event.dispatcher.interval", 0);
    if (interval > 0) {
        EventDispatcherMetrics.getInstance().start(interval);
    }
}
```

### 3. Clean Up Resources at Application Shutdown
```java
public void stopServer() {
    EventDispatcherMetrics.getInstance().stop();
}
```

### 4. Access Metrics via HTTP API
```bash
# Server metrics
curl http://localhost:8081/metrics

# Event dispatcher queue metrics
curl http://localhost:8081/metrics/event-dispatcher

# Prometheus format
curl http://localhost:8081/metrics/prometheus
```

## Future Extension Directions

- [ ] JMX integration (expose metrics through MBeans)
- [ ] Metrics persistence (store to time-series database)
- [ ] Alerting system (threshold monitoring)
- [ ] More dimensional metrics (by protocol, by port, etc.)
- [ ] Custom metrics registration mechanism

## Related Documentation

- [MONITORING.md](MONITORING.md) - HTTP monitoring endpoints documentation
- [ServerMetrics.java](../src/main/java/com/akakata/metrics/ServerMetrics.java) - Server metrics source code
- [EventDispatcherMetrics.java](../src/main/java/com/akakata/metrics/EventDispatcherMetrics.java) - Event dispatcher metrics source code
- [MetricsHandler.java](../src/main/java/com/akakata/server/http/MetricsHandler.java) - HTTP metrics handler source code