# Zea-tcp 性能分析与优化建议

## 📋 当前架构分析

### 消息处理流程

```
Client → Netty IO Thread → Protocol Handler → ShardedEventDispatcher
                                                ↓ (异步，不阻塞 IO)
                                        分片队列 (ManyToOne, 32K each)
                                                ↓
                                        Agent Thread (per shard)
                                                ↓
                                        DefaultSessionEventHandler
                                                ↓
                                        case Events.SESSION_MESSAGE:
                                                ↓
                                        onDataIn() → 业务逻辑
```

### 关键组件

1. **ShardedEventDispatcher**
   - 分片数量：`Math.max(2, CPU 核心数的最高位)`
   - 每个分片：独立的 `AgronaEventDispatcher`
   - 每个分片：独立的 `ManyToOneConcurrentArrayQueue` (32K 容量)
   - 每个分片：独立的 Agent 线程

2. **DefaultSessionEventHandler**
   - 处理 `Events.SESSION_MESSAGE`（高频游戏消息）
   - 处理 `Events.NETWORK_MESSAGE`（广播消息）
   - 处理生命周期事件（登录、登出、异常等）

3. **事件类型分布**
   - **高频**：`SESSION_MESSAGE`, `NETWORK_MESSAGE` (游戏中大部分时间)
   - **低频**：`LOG_IN`, `LOG_OUT`, `CONNECT`, `DISCONNECT` (生命周期)

---

## 🔍 性能瓶颈分析

### 1. **DefaultSessionEventHandler.onDataIn() 的问题**

当前实现（第 74-83 行）：

```java
protected void onDataIn(Event event) {
    LOG.debug("On data in");

    if (session != null) {
        PlayerSession playerSession = (PlayerSession) session;
        NetworkEvent networkEvent = new DefaultNetworkEvent(event);  // ⚠️ 问题 1：创建新对象
        playerSession.getGame().sendBroadcast(networkEvent);        // ⚠️ 问题 2：无条件广播
    }
}
```

**问题 1：对象分配**
- 每个 `SESSION_MESSAGE` 都创建一个新的 `NetworkEvent`
- 高频场景：1000 玩家 × 60 消息/秒 = 每秒 60,000 个 `NetworkEvent` 对象
- GC 压力增加

**问题 2：无条件广播**
- `sendBroadcast()` 会发送给同一 `Game` 中的所有其他玩家
- 即使某些消息不需要广播（如私聊、客户端状态更新等）
- 带宽和 CPU 浪费

---

### 2. **ShardedEventDispatcher 的分片策略**

当前实现（`ShardedEventDispatcher` 第 38-42 行）：

```java
private int selectShard(Event event) {
    Object source = event.getSource();
    int hash = (source != null) ? source.hashCode() : 0;
    return Math.abs(hash) % shardCount;
}
```

**潜在问题：**
- 如果 `source` 是 `PlayerSession`，hash 分布可能不均匀
- 某些分片可能负载更高，导致延迟增加

---

### 3. **Event 对象的生命周期**

查看 `DefaultSessionEventHandler.onClose()` (第 136-139 行)：

```java
protected void onClose(Event event) {
    session.close();
    ReferenceCountUtil.release(event);  // ✅ 手动释放
}
```

**问题：**
- 只有在 `onClose()` 中才释放 Event
- 其他方法（`onDataIn`, `onNetworkMessage` 等）没有释放 Event
- 可能存在内存泄漏

---

## ✅ 优化建议

### 优化 1：优化 `onDataIn()` - 减少对象分配

**方案 A：消息类型判断**

不是所有 `SESSION_MESSAGE` 都需要广播，应该根据消息类型决定：

```java
protected void onDataIn(Event event) {
    if (session == null) {
        return;
    }

    PlayerSession playerSession = (PlayerSession) session;

    // 从 event 中读取消息类型（假设在 source 或 payload 中）
    int messageType = extractMessageType(event);

    switch (messageType) {
        case MSG_PLAYER_MOVE:
        case MSG_PLAYER_ATTACK:
        case MSG_CHAT_PUBLIC:
            // 需要广播的消息
            NetworkEvent networkEvent = new DefaultNetworkEvent(event);
            playerSession.getGame().sendBroadcast(networkEvent);
            break;

        case MSG_CHAT_PRIVATE:
        case MSG_CLIENT_STATE:
            // 不需要广播，直接处理
            handlePrivateMessage(playerSession, event);
            break;

        default:
            // 默认行为：广播
            NetworkEvent ne = new DefaultNetworkEvent(event);
            playerSession.getGame().sendBroadcast(ne);
            break;
    }
}
```

**收益：**
- 减少不必要的 `NetworkEvent` 对象创建
- 减少不必要的广播操作
- 降低 GC 压力和网络带宽

---

**方案 B：对象池复用**

如果大部分消息都需要广播，可以使用对象池：

```java
public class DefaultSessionEventHandler implements SessionEventHandler {

    // 每个 handler 一个对象池，避免线程竞争
    private final ObjectPool<DefaultNetworkEvent> networkEventPool =
        new ObjectPool<>(() -> new DefaultNetworkEvent(), 16);

    protected void onDataIn(Event event) {
        if (session == null) {
            return;
        }

        PlayerSession playerSession = (PlayerSession) session;

        // 从对象池获取
        DefaultNetworkEvent networkEvent = networkEventPool.borrow();
        try {
            networkEvent.setSource(event.getSource());
            networkEvent.setType(Events.NETWORK_MESSAGE);
            networkEvent.setDeliveryGuaranty(event.getDeliveryGuaranty());
            // ... 复制其他字段

            playerSession.getGame().sendBroadcast(networkEvent);
        } finally {
            // 归还到对象池
            networkEventPool.returnObject(networkEvent);
        }
    }
}
```

**收益：**
- 零 GC 压力（对象复用）
- 需要实现对象池逻辑

---

### 优化 2：优化分片策略

当前的 `selectShard()` 依赖 `source.hashCode()`，可能分布不均。

**改进方案：使用 Session ID**

```java
private int selectShard(Event event) {
    Object source = event.getSource();

    // 如果 source 是 Session，使用 session ID 进行分片
    if (source instanceof Session) {
        Session session = (Session) source;
        Object id = session.getId();
        if (id != null) {
            // 使用更好的 hash 算法
            return Math.floorMod(id.hashCode(), shardCount);
        }
    }

    // 默认使用 source 的 hashCode
    int hash = (source != null) ? source.hashCode() : 0;
    return Math.floorMod(hash, shardCount);
}
```

**收益：**
- 更均匀的负载分布
- 同一个 session 的消息总是在同一个分片（有利于缓存）

---

### 优化 3：正确释放 Event 对象

确保所有 Event 处理完后都释放：

```java
protected void onDataIn(Event event) {
    try {
        if (session != null) {
            PlayerSession playerSession = (PlayerSession) session;
            NetworkEvent networkEvent = new DefaultNetworkEvent(event);
            playerSession.getGame().sendBroadcast(networkEvent);
        }
    } finally {
        // 确保释放
        ReferenceCountUtil.release(event);
    }
}

protected void onNetworkMessage(NetworkEvent event) {
    try {
        MessageSender sender = session.getSender();
        if (sender != null) {
            sender.sendMessage(event);
        }
    } finally {
        ReferenceCountUtil.release(event);
    }
}
```

**收益：**
- 避免内存泄漏
- Netty ByteBuf 及时回收

---

### 优化 4：批量处理（未来扩展）

如果延迟要求不高，可以批量处理消息以提高吞吐量：

```java
protected void onDataIn(Event event) {
    // 将消息添加到批次缓冲区
    messageBatch.add(event);

    // 达到批次大小或超时后，批量广播
    if (messageBatch.size() >= BATCH_SIZE || isTimeout()) {
        broadcastBatch(messageBatch);
        messageBatch.clear();
    }
}
```

**收益：**
- 减少 `sendBroadcast()` 调用次数
- 提高吞吐量
- 增加延迟（不适合实时性要求高的游戏）

---

## 📊 性能对比预估

假设场景：1000 个玩家，每个玩家 60 消息/秒

### 当前实现

- **对象创建**：60,000 个 `NetworkEvent` / 秒
- **GC 频率**：每秒多次 Young GC
- **广播操作**：60,000 次 / 秒（包括不必要的广播）

### 优化后（方案 A：消息类型判断）

假设 30% 的消息不需要广播：

- **对象创建**：42,000 个 `NetworkEvent` / 秒 （减少 30%）
- **GC 频率**：降低 30%
- **广播操作**：42,000 次 / 秒（减少 30%）

**收益：30% 的 CPU 和带宽节省**

### 优化后（方案 B：对象池）

- **对象创建**：0 个 / 秒（完全复用）
- **GC 频率**：接近 0
- **广播操作**：60,000 次 / 秒（不变）

**收益：消除 GC 压力，CPU 节省 ~10-15%**

---

## 🎯 推荐实施顺序

1. **立即实施**：优化 3（正确释放 Event）
   - 避免内存泄漏
   - 风险低，收益明显

2. **短期实施**：优化 1-A（消息类型判断）
   - 减少不必要的广播
   - 实现简单，收益明显

3. **中期实施**：优化 2（优化分片策略）
   - 提高负载均衡
   - 需要测试验证

4. **长期实施**：优化 1-B（对象池）或优化 4（批量处理）
   - 需要更多开发和测试
   - 适合高负载场景

---

## 🔧 监控建议

添加以下监控指标：

```java
// 在 DefaultSessionEventHandler 中添加
private final AtomicLong messageCount = new AtomicLong(0);
private final AtomicLong broadcastCount = new AtomicLong(0);

protected void onDataIn(Event event) {
    messageCount.incrementAndGet();

    // ... 处理逻辑

    if (needsBroadcast) {
        broadcastCount.incrementAndGet();
    }
}

// 定期输出统计
public void logStats() {
    long messages = messageCount.getAndSet(0);
    long broadcasts = broadcastCount.getAndSet(0);
    LOG.info("Session {} - Messages: {}, Broadcasts: {} ({:.2f}%)",
             session.getId(), messages, broadcasts,
             broadcasts * 100.0 / Math.max(messages, 1));
}
```

**监控内容：**
- 每个 session 的消息频率
- 广播消息占比
- ShardedEventDispatcher 的队列深度
- Agent 线程的 CPU 使用率

---

## 总结

**关键认识：**
1. ✅ 你的架构已经**不阻塞 Netty IO 线程**（通过 ShardedEventDispatcher 异步处理）
2. ✅ 分片架构避免了单队列竞争
3. ⚠️ 性能瓶颈主要在 `DefaultSessionEventHandler.onDataIn()` 的对象分配和无条件广播

**不要做的：**
- ❌ 不要绕过 `ShardedEventDispatcher`（会失去线程隔离）
- ❌ 不要在 Netty IO 线程中直接处理业务逻辑

**应该做的：**
- ✅ 优化 `DefaultSessionEventHandler` 的消息处理逻辑
- ✅ 减少不必要的对象分配和广播
- ✅ 正确管理 Event 对象的生命周期

---

**作者**: Kelvin
**日期**: 2025-10-16
**版本**: 1.0
