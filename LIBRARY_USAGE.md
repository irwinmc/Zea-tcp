# Zea-tcp Library Usage Guide

Zea-tcp is a high-performance game server framework that can be used as a library in your projects.

## Table of Contents
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Extending CommandLine](#extending-commandline)
- [Lifecycle Hooks](#lifecycle-hooks)
- [Advanced Usage](#advanced-usage)
- [Examples](#examples)

---

## Installation

### Option 1: Maven Dependency (Local)

```bash
cd Zea-tcp
mvn clean install
```

Then add to your `pom.xml`:

```xml
<dependency>
    <groupId>com.akakata</groupId>
    <artifactId>Zea-tcp</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### Option 2: Direct JAR

```bash
mvn clean package
# Use target/Zea-tcp-1.0-SNAPSHOT.jar in your project
```

---

## Quick Start

### 1. Create Your Server Class

```java
package com.yourcompany.game;

import com.akakata.CommandLine;

public class GameServer extends CommandLine {

    public static void main(String[] args) {
        new GameServer().run(args);
    }

    @Override
    protected void beforeServerStartup() throws Exception {
        System.out.println("Game server starting...");
    }
}
```

### 2. Run Your Server

```bash
java -cp your-game.jar:Zea-tcp-1.0-SNAPSHOT.jar com.yourcompany.game.GameServer
```

---

## Extending CommandLine

`CommandLine` is the base class that manages the server lifecycle. Extend it to create your custom server:

```java
public class MyGameServer extends CommandLine {

    public static void main(String[] args) {
        new MyGameServer().run(args);
    }

    // Override lifecycle hooks (see below)
}
```

---

## Lifecycle Hooks

The `CommandLine` base class provides 4 lifecycle hooks:

### 1. `beforeServerStartup()`
**Called before servers start**

Use cases:
- Load game configuration
- Connect to database
- Initialize game world state
- Load player data

```java
@Override
protected void beforeServerStartup() throws Exception {
    logger.info("Loading game configuration...");
    GameConfig.load("config/game.json");

    logger.info("Connecting to database...");
    database = DatabaseFactory.create();

    logger.info("Loading game world...");
    gameWorld = new GameWorld();
    gameWorld.load();
}
```

### 2. `afterServerStartup()`
**Called after servers are running**

Use cases:
- Start background tasks
- Run health checks
- Send "server ready" notifications
- Start game loops

```java
@Override
protected void afterServerStartup() throws Exception {
    logger.info("Starting game tick scheduler...");
    gameTickScheduler = Executors.newScheduledThreadPool(1);
    gameTickScheduler.scheduleAtFixedRate(
        () -> gameWorld.tick(),
        0, 50, TimeUnit.MILLISECONDS
    );

    logger.info("Server ready for players!");
}
```

### 3. `beforeServerShutdown()`
**Called before servers stop**

Use cases:
- Save game state
- Notify players of shutdown
- Persist player data
- Stop accepting new connections

```java
@Override
protected void beforeServerShutdown() {
    logger.info("Saving game state...");
    gameWorld.save();

    logger.info("Saving player data...");
    playerManager.saveAll();

    logger.info("Notifying players of shutdown...");
    playerManager.broadcastMessage("Server shutting down in 5 seconds...");
}
```

### 4. `afterServerShutdown()`
**Called after servers stop**

Use cases:
- Close database connections
- Release resources
- Final cleanup
- Write shutdown logs

```java
@Override
protected void afterServerShutdown() {
    logger.info("Closing database connection...");
    if (database != null) {
        database.close();
    }

    logger.info("Stopping background tasks...");
    if (gameTickScheduler != null) {
        gameTickScheduler.shutdown();
    }

    logger.info("Shutdown complete");
}
```

---

## Advanced Usage

### Custom Configuration Path

```java
@Override
protected ServerContext createServerContext() throws Exception {
    String configPath = System.getProperty("config.path", "my-config.properties");
    return new ServerContext(configPath);
}
```

### Custom Termination Logic

```java
@Override
protected void awaitTermination() throws InterruptedException {
    // Custom logic, e.g., wait for specific condition
    while (isRunning() && someCondition()) {
        Thread.sleep(1000);
    }
}
```

### Access Server Components

```java
@Override
protected void afterServerStartup() throws Exception {
    // Access ServerContext
    ServerContext ctx = getContext();

    // Access ServerManager
    ServerManager manager = getServerManager();

    // Check if running
    if (isRunning()) {
        logger.info("Server is running!");
    }
}
```

---

## Examples

See the test directory for complete examples:

### Basic Test Server
**File:** `src/test/java/com/akakata/TestServer.java`

Simple server with minimal hooks for quick testing.

```bash
mvn compile
java -cp target/Zea-tcp-1.0-SNAPSHOT.jar com.akakata.TestServer
```

### Advanced Test Server
**File:** `src/test/java/com/akakata/AdvancedTestServer.java`

Demonstrates:
- Custom configuration
- Background task management
- Metrics collection
- Graceful shutdown with state persistence

```bash
mvn compile
java -cp target/Zea-tcp-1.0-SNAPSHOT.jar com.akakata.AdvancedTestServer
```

---

## Architecture Overview

```
Your Game Server (extends CommandLine)
    ↓
CommandLine (lifecycle management)
    ↓
ServerContext (dependency injection)
    ↓
ServerManager (manages all servers)
    ↓
├─ TCP Server (port 8090)
├─ HTTP Server (port 8081)
└─ WebSocket Server (port 8300)
```

---

## Configuration

Default configuration file: `props/conf.properties`

Key settings:
```properties
# Thread configuration
bossThreadCount=1
workerThreadCount=4

# Server ports
tcp.port=8090
http.port=8081
web.socket.port=8300

# Socket options
so.backlog=100
so.reuseaddr=true
so.keepalive=true
tcp.nodelay=true

# Metrics
metrics.event.interval.seconds=30
```

---

## Best Practices

1. **Always call super methods** when overriding hooks:
   ```java
   @Override
   protected void beforeServerStartup() throws Exception {
       super.beforeServerStartup(); // If base class has logic
       // Your logic here
   }
   ```

2. **Handle exceptions properly** in hooks:
   ```java
   @Override
   protected void beforeServerShutdown() {
       try {
           cleanupResources();
       } catch (Exception e) {
           logger.error("Cleanup failed", e);
       }
   }
   ```

3. **Use dependency injection** through ServerContext:
   ```java
   ServerManager manager = context.getBean(ServerManager.class);
   ```

4. **Don't block in hooks** - use async operations for long tasks

5. **Test graceful shutdown** with `Ctrl+C` during development

---

## Support

- GitHub Issues: https://github.com/yourcompany/Zea-tcp/issues
- Documentation: See `CLAUDE.md` for detailed architecture
- Examples: `src/test/java/com/akakata/`

---

## License

[Your License Here]
