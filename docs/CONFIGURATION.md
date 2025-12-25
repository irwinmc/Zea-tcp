# Configuration Guide

Zea-tcp uses a properties file for configuration. This guide explains all available options.

## Configuration File Location

Default: `src/main/resources/props/conf.properties`

To use a custom configuration file:

```java
@Override
protected ServerContext createServerContext() throws Exception {
    return new ServerContext("path/to/your/config.properties");
}
```

---

## Server Enable/Disable

Control which servers start on launch.

### Configuration Keys

| Key | Default | Description |
|-----|---------|-------------|
| `server.tcp.enabled` | `true` | Enable/disable TCP server |
| `server.http.enabled` | `true` | Enable/disable HTTP server |
| `server.websocket.enabled` | `true` | Enable/disable WebSocket server |

### Examples

#### Only WebSocket Server
```properties
server.tcp.enabled=false
server.http.enabled=false
server.websocket.enabled=true
```

#### HTTP + WebSocket (No TCP)
```properties
server.tcp.enabled=false
server.http.enabled=true
server.websocket.enabled=true
```

#### Only TCP Server
```properties
server.tcp.enabled=true
server.http.enabled=false
server.websocket.enabled=false
```

---

## Port Configuration

Configure the listening ports for each server.

| Key | Default | Description |
|-----|---------|-------------|
| `tcp.port` | `8090` | TCP server port |
| `http.port` | `8081` | HTTP server port |
| `web.socket.port` | `8300` | WebSocket server port |

### Examples

**Development (avoid conflicts with other services):**
```properties
tcp.port=18090
http.port=18081
web.socket.port=18300
```

**Production (standard ports):**
```properties
tcp.port=8090
http.port=80
web.socket.port=443
```

---

## Thread Configuration

Configure Netty event loop threads.

| Key | Default | Description |
|-----|---------|-------------|
| `bossThreadCount` | `2` | Boss threads (accept connections) |
| `workerThreadCount` | `2` | Worker threads (handle I/O) |

### Recommendations

**Minimal (development/testing):**
```properties
bossThreadCount=1
workerThreadCount=2
```

**Recommended (production):**
```properties
bossThreadCount=2
workerThreadCount=8  # CPU cores
```

**High-load (enterprise):**
```properties
bossThreadCount=4
workerThreadCount=16
```

### Guidelines

- **Boss threads**: 1-4 threads is usually enough
- **Worker threads**: Set to number of CPU cores for best performance
- More threads ≠ better performance (context switching overhead)

---

## Socket Options

Configure low-level socket behavior.

| Key | Default | Description |
|-----|---------|-------------|
| `so.backlog` | `100` | Maximum queue length for incoming connections |
| `so.reuseaddr` | `true` | Allow reuse of local addresses |
| `so.keepalive` | `true` | Send TCP keepalive messages |
| `tcp.nodelay` | `true` | Disable Nagle's algorithm (reduce latency) |

### Examples

**Standard settings:**
```properties
so.backlog=100
so.reuseaddr=true
so.keepalive=true
tcp.nodelay=true
```

**High-throughput:**
```properties
so.backlog=1024
so.reuseaddr=true
so.keepalive=true
tcp.nodelay=true
```

**Low-latency (gaming):**
```properties
so.backlog=512
so.reuseaddr=true
so.keepalive=false  # Reduce overhead
tcp.nodelay=true     # Critical for low latency
```

---

## Protocol Configuration

Choose the message encoding protocol.

| Key | Default | Options | Description |
|-----|---------|---------|-------------|
| `protocol.type` | `JSON` | `JSON`, `SBE` | Message encoding format |

### JSON Protocol
- **Pros**: Human-readable, easy debugging
- **Cons**: Larger message size, slower parsing
- **Use case**: Development, debugging, REST APIs

```properties
protocol.type=JSON
```

### SBE Protocol
- **Pros**: High performance, compact binary
- **Cons**: Not human-readable
- **Use case**: Production, high-frequency messaging

```properties
protocol.type=SBE
```

---

## Reconnect Configuration

| Key | Default | Description |
|-----|---------|-------------|
| `reconnect.delay` | `300000` | Delay before reconnect attempt (milliseconds) |

```properties
# 5 minutes
reconnect.delay=300000

# 30 seconds
reconnect.delay=30000

# 1 minute
reconnect.delay=60000
```

---

## Complete Configuration Examples

### Example 1: Development Server

```properties
# Only WebSocket for testing
server.tcp.enabled=false
server.http.enabled=false
server.websocket.enabled=true

# High port to avoid conflicts
web.socket.port=18300

# Minimal threads
bossThreadCount=1
workerThreadCount=2

# JSON for easier debugging
protocol.type=JSON

# Standard socket options
so.backlog=100
so.reuseaddr=true
so.keepalive=true
tcp.nodelay=true
```

### Example 2: Production Game Server

```properties
# TCP + WebSocket
server.tcp.enabled=true
server.http.enabled=false
server.websocket.enabled=true

# Standard ports
tcp.port=8090
web.socket.port=8300

# Optimized threads (8-core CPU)
bossThreadCount=2
workerThreadCount=8

# Binary protocol for performance
protocol.type=SBE

# High-throughput settings
so.backlog=1024
so.reuseaddr=true
so.keepalive=true
tcp.nodelay=true
```

### Example 3: Web Application Server

```properties
# HTTP + WebSocket only
server.tcp.enabled=false
server.http.enabled=true
server.websocket.enabled=true

# Standard web ports
http.port=8081
web.socket.port=8300

# Moderate threads
bossThreadCount=2
workerThreadCount=4

# JSON for REST API compatibility
protocol.type=JSON

# Standard settings
so.backlog=512
so.reuseaddr=true
so.keepalive=true
tcp.nodelay=true
```

### Example 4: Microservice (HTTP REST API Only)

```properties
# HTTP only
server.tcp.enabled=false
server.http.enabled=true
server.websocket.enabled=false

# Standard port
http.port=8081

# Minimal threads
bossThreadCount=1
workerThreadCount=4

# JSON for REST
protocol.type=JSON

# Standard settings
so.backlog=256
so.reuseaddr=true
so.keepalive=true
tcp.nodelay=true
```

---

## Environment-Specific Configuration

### Using System Properties

Override configuration at runtime:

```bash
java -Dtcp.port=9090 -Dserver.http.enabled=false -jar your-server.jar
```

### Multiple Configuration Files

Create environment-specific configs:

```
conf-dev.properties
conf-staging.properties
conf-prod.properties
```

Load based on environment:

```java
@Override
protected ServerContext createServerContext() throws Exception {
    String env = System.getProperty("env", "dev");
    String configPath = "props/conf-" + env + ".properties";
    return new ServerContext(configPath);
}
```

Run with:
```bash
java -Denv=prod -jar your-server.jar
```

---

## Validation

When the server starts, you'll see logs indicating which servers are enabled:

```
2025-01-19 10:30:45  INFO --- ServerManagerImpl: Starting servers based on configuration...
2025-01-19 10:30:45  INFO --- ServerManagerImpl: Starting TCP server on port 8090...
2025-01-19 10:30:45  INFO --- ServerManagerImpl: TCP server started successfully
2025-01-19 10:30:45  INFO --- ServerManagerImpl: HTTP server disabled by configuration
2025-01-19 10:30:45  INFO --- ServerManagerImpl: Starting WebSocket server on port 8300...
2025-01-19 10:30:45  INFO --- ServerManagerImpl: WebSocket server started successfully
2025-01-19 10:30:45  INFO --- ServerManagerImpl: Total servers started: 2
```

---

## Troubleshooting

### Problem: No servers start

**Check:**
- All `server.*.enabled` are not set to `false`
- Configuration file is being loaded correctly

**Solution:**
```properties
# At least one server must be enabled
server.tcp.enabled=true
```

### Problem: Port already in use

**Check:**
- Another process is using the port
- Previous server instance didn't shut down

**Solution:**
```bash
# Find process using port
lsof -i :8090

# Kill process
kill -9 <PID>

# Or change port in config
tcp.port=8091
```

### Problem: Low performance

**Check:**
- Thread count
- Socket options
- Protocol type

**Solution:**
```properties
# Increase worker threads
workerThreadCount=8

# Optimize for throughput
so.backlog=1024
tcp.nodelay=true

# Use binary protocol
protocol.type=SBE
```
