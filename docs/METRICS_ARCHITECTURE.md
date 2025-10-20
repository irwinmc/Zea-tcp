# Metrics Architecture 指标架构

## 📊 架构概述

Zea-tcp 的指标系统采用**模块化设计**，提供两个独立的指标收集器。

```
指标系统
    ├── ServerMetrics (服务器指标)
    │   ├── 连接指标
    │   ├── 流量指标
    │   ├── 系统指标
    │   └── 错误指标
    │
    └── EventDispatcherMetrics (事件分发器指标)
        └── 队列状态监控
```

## 🎯 设计原则

### 1. **单一职责原则 (SRP)**
每个指标收集器只负责一类指标：
- `ServerMetrics` → 服务器级别指标
- `EventDispatcherMetrics` → 事件分发器队列指标

### 2. **单例模式 (Singleton Pattern)**
所有指标收集器都采用单例模式：
- `ServerMetrics.getInstance()` - 获取服务器指标单例
- `EventDispatcherMetrics.getInstance()` - 获取事件分发器指标单例
- 保证全局唯一实例，避免重复创建

### 3. **线程安全**
- `ServerMetrics`: 使用 `AtomicLong` 确保线程安全
- `EventDispatcherMetrics`: 使用 `AtomicBoolean`、`AtomicInteger`、`AtomicReference` 确保线程安全

## 📦 核心组件

### 1. ServerMetrics

**收集的指标：**

| 分类 | 指标 | 说明 |
|------|------|------|
| **连接** | `totalConnections` | 累计连接数 |
| | `activeConnections` | 当前活跃连接数 |
| | `currentChannelCount` | 当前Channel数量 |
| **流量** | `totalMessagesReceived` | 累计接收消息数 |
| | `totalMessagesSent` | 累计发送消息数 |
| | `totalBytesReceived` | 累计接收字节数 |
| | `totalBytesSent` | 累计发送字节数 |
| **系统** | `usedMemoryMB` | 已用内存（MB）|
| | `maxMemoryMB` | 最大内存（MB）|
| | `threadCount` | 线程数 |
| | `cpuLoad` | CPU负载 |
| | `uptimeSeconds` | 运行时间（秒）|
| **错误** | `totalErrors` | 累计错误数 |

**使用示例：**
```java
// 直接访问单例
ServerMetrics serverMetrics = ServerMetrics.getInstance();

// 记录指标
serverMetrics.recordConnection();
serverMetrics.recordDisconnection();
serverMetrics.recordMessageReceived();
serverMetrics.recordBytesReceived(1024);

// 读取指标
long total = serverMetrics.getTotalConnections();
long active = serverMetrics.getActiveConnections();
double cpuLoad = serverMetrics.getCpuLoad();
```

### 2. EventDispatcherMetrics

**功能：**
- 周期性监控事件分发器队列状态
- 输出总队列大小和各分片队列大小
- 帮助识别性能瓶颈和热点分片

**使用场景：**
- 压力测试
- 负载均衡验证
- 性能调优
- 生产环境监控

**使用示例：**
```java
// 直接使用单例
EventDispatcherMetrics metrics = EventDispatcherMetrics.getInstance();
metrics.start(5); // 每5秒收集一次

// 获取指标数据
int totalQueueSize = metrics.getTotalQueueSize();
int[] perShardSizes = metrics.getPerShardQueueSizes();
int maxShardSize = metrics.getMaxShardQueueSize();

// 停止监控
metrics.stop();
```

**HTTP访问示例：**
```bash
# 获取事件分发器队列指标
curl http://localhost:8081/metrics/event-dispatcher

# 返回示例
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

## 🔧 集成方式

### 方式1：在 Handler 中使用 ServerMetrics

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

### 方式2：在启动时配置 EventDispatcherMetrics

```java
public class MyServer {
    public void start() {
        // 启动事件分发器监控（可选）
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

## 📊 HTTP API 集成

`MetricsHandler` 已经自动使用 `ServerMetrics` 和 `EventDispatcherMetrics`：

```java
// MetricsHandler 内部
private final ServerMetrics metrics = ServerMetrics.getInstance();

private void handleAllMetrics(ChannelHandlerContext ctx) {
    // 使用 ServerMetrics 获取数据并返回JSON
}

private void handleEventDispatcherMetrics(ChannelHandlerContext ctx) {
    EventDispatcherMetrics edMetrics = EventDispatcherMetrics.getInstance();
    // 获取事件分发器队列数据并返回JSON
}
```

**可用的HTTP端点：**
- `GET /metrics` - 所有指标
- `GET /metrics/connections` - 连接指标
- `GET /metrics/traffic` - 流量指标
- `GET /metrics/system` - 系统指标
- `GET /metrics/event-dispatcher` - 事件分发器队列指标
- `GET /metrics/prometheus` - Prometheus格式

## 🏗️ 扩展新的指标收集器

### 步骤1：创建新的指标收集器

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

### 步骤2：在 MetricsHandler 中暴露端点

```java
public class MetricsHandler extends AbstractHttpHandler {
    public void handleMetrics(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();

        if (uri.startsWith("/metrics/database")) {
            handleDatabaseMetrics(ctx);
            return;
        }
        // ... 其他端点 ...
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

## 🎨 UML 类图

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
                       │ 使用
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

## 📋 配置示例

```properties
# conf.properties

# Event dispatcher metrics monitoring interval (seconds)
# Set to 0 or negative to disable
metrics.event.dispatcher.interval=5

# HTTP metrics endpoint port
http.port=8081
```

## 🧪 测试用法

```java
public class MetricsTest {
    @Test
    public void testServerMetrics() {
        ServerMetrics metrics = ServerMetrics.getInstance();

        // 记录一些指标
        metrics.recordConnection();
        metrics.recordMessageReceived();
        metrics.recordBytesReceived(1024);

        // 验证
        assertEquals(1, metrics.getTotalConnections());
        assertEquals(1, metrics.getTotalMessagesReceived());
        assertEquals(1024, metrics.getTotalBytesReceived());

        // 清理
        metrics.reset();
    }

    @Test
    public void testEventDispatcherMetrics() {
        EventDispatcherMetrics metrics = EventDispatcherMetrics.getInstance();

        // 启动监控
        metrics.start(1);

        // 等待收集
        Thread.sleep(1500);

        // 验证数据已收集
        assertTrue(metrics.getLastUpdateTimestamp() > 0);
        assertTrue(metrics.getShardCount() > 0);

        // 停止监控
        metrics.stop();
    }
}
```

## 🎯 最佳实践

### 1. 直接使用单例模式
```java
// ✅ 推荐 - 直接使用单例
ServerMetrics.getInstance().recordConnection();
EventDispatcherMetrics.getInstance().start(5);
```

### 2. 在应用启动时配置监控
```java
public void startServer() {
    // 根据配置启动事件分发器监控
    long interval = config.getLong("metrics.event.dispatcher.interval", 0);
    if (interval > 0) {
        EventDispatcherMetrics.getInstance().start(interval);
    }
}
```

### 3. 在应用关闭时清理资源
```java
public void stopServer() {
    EventDispatcherMetrics.getInstance().stop();
}
```

### 4. 通过 HTTP API 访问指标
```bash
# 服务器指标
curl http://localhost:8081/metrics

# 事件分发器队列指标
curl http://localhost:8081/metrics/event-dispatcher

# Prometheus格式
curl http://localhost:8081/metrics/prometheus
```

## 🔮 未来扩展方向

- [ ] JMX集成（通过MBeans暴露指标）
- [ ] 指标持久化（存储到时序数据库）
- [ ] 告警系统（阈值监控）
- [ ] 更多维度的指标（按协议、按端口等）
- [ ] 自定义指标注册机制

## 📚 相关文档

- [MONITORING.md](MONITORING.md) - HTTP监控端点文档
- [ServerMetrics.java](../src/main/java/com/akakata/metrics/ServerMetrics.java) - 服务器指标源码
- [EventDispatcherMetrics.java](../src/main/java/com/akakata/metrics/EventDispatcherMetrics.java) - 事件分发器指标源码
- [MetricsHandler.java](../src/main/java/com/akakata/server/http/MetricsHandler.java) - HTTP指标处理器源码
