# Zea TCP Transport

[![Maven Central](https://img.shields.io/maven-central/v/com.akakata/zea-tcp.svg)](https://central.sonatype.com/artifact/com.akakata/zea-tcp)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://openjdk.java.net/)

High-performance Netty transport and protocol gateway for the Zea platform, providing TCP, HTTP, and WebSocket server support.

## Features

- 🚀 **High Performance**: Asynchronous non-blocking I/O based on Netty
- 🔌 **Multi-Protocol Support**: TCP, HTTP, WebSocket
- 📊 **Built-in Monitoring**: Real-time metrics collection and HTTP monitoring endpoints
- ⚙️ **Flexible Configuration**: Selective server enable/disable support
- 🔧 **Dependency Injection**: Dagger 2-based dependency management
- 📈 **Extensible**: Modular architecture, easy to extend
- 🎯 **Production Ready**: Enterprise-grade features including caching, logging, and metrics

## Quick Start

### Maven Dependency

```xml
<dependency>
    <groupId>com.akakata</groupId>
    <artifactId>zea-tcp</artifactId>
    <version>0.7.8</version>
</dependency>
```

### Create Your Server

```java
package com.yourcompany.server;

import com.akakata.CommandLine;

public class MyServer extends CommandLine {

    public static void main(String[] args) {
        new MyServer().run(args);
    }

    @Override
    protected void beforeServerStartup() throws Exception {
        System.out.println("Initializing before server startup...");
        // Load configuration, connect to database, etc.
    }

    @Override
    protected void afterServerStartup() throws Exception {
        System.out.println("Server startup completed!");
        // Start background tasks, etc.
    }
}
```

### Run Your Server

```bash
java -jar your-server.jar
```

Default ports:
- TCP: 8090
- HTTP: 8081  
- WebSocket: 8300

## Architecture Overview

```
Application Layer (Your Application)
    ↓
CommandLine (Lifecycle Management)
    ↓
ServerContext (Dependency Injection Container)
    ↓
ServerManager (Server Manager)
    ↓
├─ TCP Server (Netty)
├─ HTTP Server (Netty) 
└─ WebSocket Server (Netty)
```

## Lifecycle Hooks

`CommandLine` provides 4 lifecycle hooks for customization:

### 1. beforeServerStartup()
**Called before servers start**

```java
@Override
protected void beforeServerStartup() throws Exception {
    logger.info("Loading application configuration...");
    AppConfig.load("config/app.json");

    logger.info("Connecting to database...");
    database = DatabaseFactory.create();

    logger.info("Initializing cache...");
    cache = CacheManager.getInstance();
}
```

### 2. afterServerStartup()
**Called after servers start**

```java
@Override
protected void afterServerStartup() throws Exception {
    logger.info("Starting scheduled tasks...");
    scheduler = Executors.newScheduledThreadPool(2);
    scheduler.scheduleAtFixedRate(
        () -> performHealthCheck(),
        0, 30, TimeUnit.SECONDS
    );

    logger.info("Server ready, waiting for connections...");
}
```

### 3. beforeServerShutdown()
**Called before servers stop**

```java
@Override
protected void beforeServerShutdown() {
    logger.info("Saving application state...");
    stateManager.saveState();

    logger.info("Notifying clients of server shutdown...");
    clientManager.broadcastShutdownNotice();
}
```

### 4. afterServerShutdown()
**Called after servers stop**

```java
@Override
protected void afterServerShutdown() {
    logger.info("Closing database connections...");
    if (database != null) {
        database.close();
    }

    logger.info("Stopping scheduled tasks...");
    if (scheduler != null) {
        scheduler.shutdown();
    }

    logger.info("Cleanup completed");
}
```

## Configuration

### Basic Configuration

Configuration file: `src/main/resources/props/conf.properties`

```properties
# Server enable/disable
server.tcp.enabled=true
server.http.enabled=true
server.websocket.enabled=true

# Port configuration
tcp.port=8090
http.port=8081
web.socket.port=8300

# Thread configuration
bossThreadCount=2
workerThreadCount=8

# Socket options
so.backlog=1024
so.reuseaddr=true
so.keepalive=true
tcp.nodelay=true

# Protocol type (JSON/SBE)
protocol.type=JSON
```

### Custom Configuration Path

```java
@Override
protected ServerContext createServerContext() throws Exception {
    String configPath = System.getProperty("config.path", "my-config.properties");
    return new ServerContext(configPath);
}
```

### Environment-Specific Configuration

```bash
# Development environment
java -Denv=dev -jar server.jar

# Production environment  
java -Denv=prod -Dtcp.port=9090 -jar server.jar
```

## Monitoring and Metrics

### HTTP Monitoring Endpoints

After server startup, you can access real-time metrics via HTTP endpoints:

```bash
# All metrics
curl http://localhost:8081/metrics

# Connection metrics
curl http://localhost:8081/metrics/connections

# Traffic metrics
curl http://localhost:8081/metrics/traffic

# System metrics
curl http://localhost:8081/metrics/system

# Event dispatcher queue metrics
curl http://localhost:8081/metrics/event-dispatcher

# Prometheus format
curl http://localhost:8081/metrics/prometheus
```

### Metrics Example

```json
{
  "connections": {
    "total_connections": 1250,
    "active_connections": 45,
    "current_channel_count": 45
  },
  "traffic": {
    "total_messages_received": 98765,
    "total_messages_sent": 87654,
    "total_bytes_received": 12345678,
    "total_bytes_sent": 11234567
  },
  "system": {
    "used_memory_mb": 256.5,
    "max_memory_mb": 1024.0,
    "thread_count": 24,
    "cpu_load": 15.6,
    "uptime_seconds": 3600
  }
}
```

## Advanced Usage

### Accessing Server Components

```java
@Override
protected void afterServerStartup() throws Exception {
    // Get ServerContext
    ServerContext ctx = getContext();

    // Get ServerManager
    ServerManager manager = getServerManager();

    // Check running status
    if (isRunning()) {
        logger.info("Server is running!");
    }

    // Get metrics
    ServerMetrics metrics = ServerMetrics.getInstance();
    long connections = metrics.getTotalConnections();
}
```

### Custom Termination Logic

```java
@Override
protected void awaitTermination() throws InterruptedException {
    // Custom logic, e.g., wait for specific condition
    while (isRunning() && shouldKeepRunning()) {
        Thread.sleep(1000);
    }
}
```

## Configuration Examples

### Development Environment

```properties
# WebSocket only for testing
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
```

### Production Environment

```properties
# TCP + WebSocket
server.tcp.enabled=true
server.http.enabled=false
server.websocket.enabled=true

# Standard ports
tcp.port=8090
web.socket.port=8300

# Optimized thread count (8-core CPU)
bossThreadCount=2
workerThreadCount=8

# Binary protocol for performance
protocol.type=SBE

# High-throughput settings
so.backlog=1024
tcp.nodelay=true
```

### Web Application Server

```properties
# HTTP + WebSocket
server.tcp.enabled=false
server.http.enabled=true
server.websocket.enabled=true

# Web ports
http.port=8081
web.socket.port=8300

# JSON for REST API
protocol.type=JSON
```

## Dependencies and Tech Stack

- **Java 21+**: Modern Java features support
- **Netty 4.2.5**: High-performance asynchronous network framework
- **Dagger 2.51.1**: Compile-time dependency injection
- **Jackson 2.18.3**: JSON/YAML processing
- **Caffeine 3.1.8**: High-performance caching
- **SLF4J + Logback**: Structured logging
- **Agrona 2.3.0**: High-performance data structures

## Performance Features

- **Zero Copy**: Efficient memory management based on Netty
- **Event-Driven**: Asynchronous non-blocking I/O model
- **Connection Pooling**: Efficient connection reuse
- **Cache Optimization**: Caffeine high-performance caching
- **Metrics Collection**: Real-time performance monitoring
- **Memory Optimization**: Agrona high-performance data structures

## Development and Build

### Build Project

```bash
# Compile
mvn clean compile

# Run tests
mvn test

# Package
mvn clean package

# Install to local repository
mvn clean install
```

### Run Examples

```bash
# Run after compilation
java -cp target/classes:target/dependency/* com.akakata.CommandLine

# Or use the provided script
./run.sh
```

## License

This project is licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).

## Contributing

Issues and Pull Requests are welcome!

## Support

- **GitHub Issues**: [https://github.com/akakata/zea-tcp/issues](https://github.com/akakata/zea-tcp/issues)
- **Documentation**: See `docs/` directory for detailed documentation
- **Examples**: See `src/test/java/com/akakata/` directory for example code

## Author

**Kelvin** - *Lead Developer and Maintainer*
- Email: kyia.x52@gmail.com
- Organization: Akakata

---

*Zea TCP Transport - High-performance network transport solutions for modern applications*