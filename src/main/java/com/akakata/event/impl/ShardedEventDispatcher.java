package com.akakata.event.impl;

import com.akakata.app.Session;
import com.akakata.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 多分片事件调度器
 * <p>
 * 将事件处理分散到多个 {@link AgronaEventDispatcher} 分片，以充分利用多核 CPU，
 * 提升整体吞吐量并降低单线程阻塞风险。
 * <p>
 * <b>架构设计：</b>
 * <ul>
 *   <li>分片数量：必须为 2 的幂（如 4, 8, 16），使用位运算快速路由</li>
 *   <li>每个分片：独立的 {@link AgronaEventDispatcher}，独立的 Agent 线程</li>
 *   <li>分片选择：使用 hashCode & mask 实现 O(1) 路由</li>
 *   <li>负载均衡：基于 session hashCode，确保同一 session 的事件始终路由到同一分片</li>
 * </ul>
 * <p>
 * <b>分片策略：</b>
 * <ul>
 *   <li><b>SessionEventHandler 注册：</b>根据 session.getId().hashCode() 分片，只注册到一个分片
 *       <br>→ 1000 个 PlayerSession = 1000 个 handler 注册（而不是 8000 个）
 *       <br>→ 每个分片约 125 个 handler（8 分片时）
 *   </li>
 *   <li><b>其他 EventHandler 注册：</b>注册到所有分片（广播语义）
 *       <br>→ 适用于需要监听所有事件的全局 handler
 *   </li>
 *   <li><b>事件触发：</b>根据 event.getType() 分片（向后兼容）
 *       <br>→ 注意：PlayerSession 直接持有其专属 shard 引用，不经过此路由
 *   </li>
 * </ul>
 * <p>
 * <b>使用场景：</b>
 * <ul>
 *   <li>作为全局共享的 EventDispatcher（EventDispatchers.sharedDispatcher()）</li>
 *   <li>为 PlayerSession 提供专属分片（通过 getShardForSession）</li>
 *   <li>需要高并发事件处理的场景（多核 CPU 利用率）</li>
 * </ul>
 * <p>
 * <b>性能特点：</b>
 * <ul>
 *   <li>总吞吐量：~2M events/sec × 分片数（如 8 分片 = 16M events/sec）</li>
 *   <li>分片路由：O(1) 时间复杂度（位运算）</li>
 *   <li>负载均衡：基于 hashCode，均匀分布</li>
 *   <li>隔离性：不同分片的事件处理互不阻塞</li>
 * </ul>
 * <p>
 * <b>使用示例：</b>
 * <pre>{@code
 * // 1. 创建 8 分片调度器
 * ShardedEventDispatcher dispatcher = new ShardedEventDispatcher(8);
 *
 * // 2. PlayerSession 获取专属分片
 * PlayerSession session = new DefaultPlayerSession();
 * EventDispatcher shard = dispatcher.getShardForSession(session);
 * session.setEventDispatcher(shard);  // 直接持有 shard 引用
 *
 * // 3. 添加 SessionEventHandler（只注册到一个分片）
 * SessionEventHandler handler = new MySessionHandler(session);
 * dispatcher.addHandler(handler);  // 自动路由到 session 的专属分片
 *
 * // 4. 事件触发（PlayerSession 直接使用 shard）
 * session.onEvent(event);  // → shard.fireEvent(event)，无路由开销
 * }</pre>
 *
 * @author Kelvin
 */
public class ShardedEventDispatcher implements EventDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(ShardedEventDispatcher.class);

    // ==================== 字段 ====================

    /**
     * 分片数组
     * <p>
     * 每个分片是独立的 AgronaEventDispatcher，有自己的 Agent 线程和事件队列。
     */
    private final AgronaEventDispatcher[] shards;

    /**
     * 分片掩码（用于快速取模）
     * <p>
     * mask = shardCount - 1，因为 shardCount 是 2 的幂。
     * 例如：shardCount=8 时，mask=7 (0b111)，hash & mask 等价于 hash % 8。
     */
    private final int mask;

    // ==================== 构造函数 ====================

    /**
     * 构造分片事件调度器
     * <p>
     * 如果 shardCount 不是 2 的幂，会自动向上取整到最近的 2 的幂。
     *
     * @param shardCount 分片数量（建议为 2 的幂，如 4, 8, 16）
     */
    public ShardedEventDispatcher(int shardCount) {
        // 确保 shardCount 是 2 的幂
        if (Integer.bitCount(shardCount) != 1) {
            int nextPowerOfTwo = Integer.highestOneBit(shardCount - 1) << 1;
            LOG.info("Shard count {} is not power of two, rounding to {}", shardCount, nextPowerOfTwo);
            shardCount = nextPowerOfTwo;
        }

        // 创建并初始化所有分片
        this.shards = new AgronaEventDispatcher[shardCount];
        for (int i = 0; i < shardCount; i++) {
            shards[i] = new AgronaEventDispatcher();
            shards[i].initialize();
        }

        this.mask = shardCount - 1;

        LOG.info("Initialized ShardedEventDispatcher with {} shards", shardCount);
    }

    // ==================== 生命周期 ====================

    /**
     * 关闭调度器
     * <p>
     * 关闭所有分片，停止所有 Agent 线程，清理所有 handler 和事件队列。
     * 此方法是线程安全的，可重复调用。
     */
    @Override
    public void close() {
        LOG.info("Closing ShardedEventDispatcher with {} shards", shards.length);
        for (AgronaEventDispatcher shard : shards) {
            shard.close();
        }
    }

    // ==================== EventDispatcher 接口实现 ====================

    /**
     * 添加事件处理器
     * <p>
     * <b>分片策略：</b>
     * <ul>
     *   <li>SessionEventHandler：根据 session.getId().hashCode() 路由到单个分片</li>
     *   <li>其他 EventHandler：注册到所有分片（广播）</li>
     * </ul>
     * <p>
     * 这种策略确保：
     * <ul>
     *   <li>每个 SessionEventHandler 只注册一次（不是 8000 次）</li>
     *   <li>同一 session 的所有 handler 都在同一分片</li>
     *   <li>负载在分片间均衡分布（基于 hashCode）</li>
     * </ul>
     *
     * @param eventHandler 事件处理器
     * @throws NullPointerException 如果 eventHandler 为 null
     */
    @Override
    public void addHandler(EventHandler eventHandler) {
        Objects.requireNonNull(eventHandler, "eventHandler");

        // SessionEventHandler 根据 session 分片（只注册到一个分片）
        if (eventHandler instanceof SessionEventHandler sessionHandler) {
            Session session = sessionHandler.getSession();
            if (session != null) {
                selectShardBySession(session).addHandler(eventHandler);
                LOG.trace("Added SessionEventHandler for session {} to shard", session.getId());
                return;
            }
        }

        // 其他类型的 handler 注册到所有分片（广播）
        for (AgronaEventDispatcher shard : shards) {
            shard.addHandler(eventHandler);
        }
        LOG.trace("Added non-session EventHandler to all {} shards", shards.length);
    }

    /**
     * 获取指定类型的所有 handler
     * <p>
     * 根据事件类型选择分片，返回该分片的 handler 列表。
     * ANY 类型随机选择一个分片（避免总是返回同一分片的结果）。
     *
     * @param eventType 事件类型
     * @return handler 列表（副本）
     */
    @Override
    public List<EventHandler> getHandlers(int eventType) {
        if (eventType == Events.ANY) {
            return randomShard().getHandlers(eventType);
        }
        return selectShard(eventType).getHandlers(eventType);
    }

    /**
     * 移除事件处理器
     * <p>
     * ANY 类型的 handler 从所有分片移除，其他类型根据事件类型选择分片移除。
     *
     * @param eventHandler 要移除的处理器
     */
    @Override
    public void removeHandler(EventHandler eventHandler) {
        if (eventHandler.getEventType() == Events.ANY) {
            // ANY handler 注册在所有分片，需要全部移除
            for (AgronaEventDispatcher shard : shards) {
                shard.removeHandler(eventHandler);
            }
        } else {
            // 特定类型的 handler 只在对应分片
            selectShard(eventHandler.getEventType()).removeHandler(eventHandler);
        }
    }

    /**
     * 移除指定事件类型的所有 handler
     *
     * @param eventType 事件类型
     */
    @Override
    public void removeHandlersForEvent(int eventType) {
        if (eventType == Events.ANY) {
            // ANY 类型在所有分片
            for (AgronaEventDispatcher shard : shards) {
                shard.removeHandlersForEvent(eventType);
            }
        } else {
            // 特定类型只在对应分片
            selectShard(eventType).removeHandlersForEvent(eventType);
        }
    }

    /**
     * 移除指定 session 的所有 handler
     * <p>
     * 遍历所有分片，找出属于该 session 的 SessionEventHandler 并移除。
     * 虽然理论上只需要检查 session 对应的分片，但为了安全起见还是检查所有分片。
     *
     * @param session 会话
     * @return true 如果移除了至少一个 handler
     */
    @Override
    public boolean removeHandlersForSession(Session session) {
        boolean removed = false;
        for (AgronaEventDispatcher shard : shards) {
            removed |= shard.removeHandlersForSession(session);
        }
        return removed;
    }

    /**
     * 清空所有 handler 和事件队列
     */
    @Override
    public void clear() {
        for (AgronaEventDispatcher shard : shards) {
            shard.clear();
        }
    }

    /**
     * 触发事件
     * <p>
     * 根据事件类型选择分片，将事件放入该分片的队列。
     * <p>
     * <b>注意：</b>这个方法主要用于非 session 相关的事件。
     * PlayerSession 会直接使用它所属的 shard 引用，不会调用这个方法，
     * 从而避免了路由开销。
     *
     * @param event 要触发的事件
     */
    @Override
    public void fireEvent(Event event) {
        if (event == null) {
            return;
        }
        // 根据事件类型分片
        selectShard(event.getType()).fireEvent(event);
    }

    // ==================== 分片选择方法 ====================

    /**
     * 根据事件类型选择分片
     * <p>
     * 使用位运算快速取模：eventType & mask 等价于 eventType % shardCount。
     *
     * @param eventType 事件类型
     * @return 对应的分片
     */
    private AgronaEventDispatcher selectShard(int eventType) {
        return shards[eventType & mask];
    }

    /**
     * 根据 session 选择分片
     * <p>
     * 使用 session.getId().hashCode() 进行分片，确保同一 session 的事件总是路由到同一个分片。
     * 这是负载均衡的核心：不同 session 的 hashCode 分布均匀，因此负载也均匀分布。
     *
     * @param session 会话
     * @return 对应的分片
     */
    private AgronaEventDispatcher selectShardBySession(Session session) {
        int hash = session.getId().hashCode();
        return shards[hash & mask];
    }

    /**
     * 随机选择一个分片
     * <p>
     * 用于某些需要随机访问分片的场景（如 getHandlers(Events.ANY)）。
     *
     * @return 随机分片
     */
    private AgronaEventDispatcher randomShard() {
        return shards[ThreadLocalRandom.current().nextInt(shards.length)];
    }

    // ==================== 公共方法 ====================

    /**
     * 获取属于某个 session 的专属分片
     * <p>
     * 用于让 PlayerSession 直接持有它所属的 shard 引用，避免每次 fireEvent 都要路由。
     * <p>
     * <b>使用场景：</b>
     * <pre>{@code
     * // PlayerSession 构造时获取专属 shard
     * EventDispatcher shard = shardedDispatcher.getShardForSession(this);
     * this.eventDispatcher = shard;
     *
     * // 之后直接使用，无路由开销
     * eventDispatcher.fireEvent(event);
     * }</pre>
     *
     * @param session 会话
     * @return 该 session 所属的分片（AgronaEventDispatcher 实例）
     */
    public EventDispatcher getShardForSession(Session session) {
        return selectShardBySession(session);
    }

    // ==================== 监控方法 ====================

    /**
     * 获取分片数量
     *
     * @return 分片数量
     */
    public int shardCount() {
        return shards.length;
    }

    /**
     * 获取所有分片队列的总大小
     * <p>
     * 用于监控整体事件积压情况。
     *
     * @return 所有分片队列大小之和
     */
    public int aggregateQueueSize() {
        int total = 0;
        for (AgronaEventDispatcher shard : shards) {
            total += shard.getQueueSize();
        }
        return total;
    }

    /**
     * 获取每个分片的队列大小
     * <p>
     * 用于监控负载均衡情况，检查是否有某个分片负载过高。
     *
     * @return 每个分片的队列大小数组
     */
    public int[] shardQueueSizes() {
        int[] sizes = new int[shards.length];
        for (int i = 0; i < shards.length; i++) {
            sizes[i] = shards[i].getQueueSize();
        }
        return sizes;
    }
}
