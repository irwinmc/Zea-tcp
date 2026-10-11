package com.akakata.event.impl;

import com.akakata.app.Session;
import com.akakata.event.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Distributes deliveries across initialized Agrona dispatcher shards.
 *
 * <p>Shard counts are rounded to a power of two. Session-aware deliveries and
 * registrations use the session identifier hash; unscoped deliveries use the event
 * type. Global handlers are registered on every shard. The original event object is
 * passed to handlers so broadcasts can reuse it without changing its identity.
 *
 * @author Kelvin
 */
public class ShardedEventDispatcher implements EventDispatcher {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ShardedEventDispatcher.class);

    // ==================== 字段 ====================

    /**
     * Initialized dispatchers indexed by the shard-selection mask.
     */
    private final AgronaEventDispatcher[] shards;

    /**
     * Power-of-two shard mask used for event and session hash selection.
     */
    private final int mask;

    // ==================== 构造函数 ====================

    /**
     * Creates and initializes the requested number of shards, rounding up to a power of two.
     *
     * @param shardCount the positive shard count, rounded up to a power of two
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
     * {@inheritDoc}
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
     * {@inheritDoc}
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
     * Returns one selected shard's handler snapshot. Wildcard queries select a random shard.
     *
     * @param eventType the event opcode or subscription selector
     * @return the selected shard's registered handlers
     */
    @Override
    public List<EventHandler> getHandlers(int eventType) {
        if (eventType == Events.ANY) {
            return randomShard().getHandlers(eventType);
        }
        return selectShard(eventType).getHandlers(eventType);
    }

    /**
     * {@inheritDoc}
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
     * {@inheritDoc}
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
     * {@inheritDoc}
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
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        for (AgronaEventDispatcher shard : shards) {
            shard.clear();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void fireEvent(Event event) {
        if (event == null) {
            return;
        }
        // 根据事件类型分片
        selectShard(event.getType()).fireEvent(event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void fireEvent(Event event, Session session) {
        if (event == null) {
            return;
        }
        selectShardBySession(Objects.requireNonNull(session, "session")).fireEvent(event, session);
    }

    // ==================== 分片选择方法 ====================

    /**
     * Selects a shard using the event opcode and power-of-two mask.
     *
     * @param eventType the event opcode or subscription selector
     * @return the selected shard
     */
    private AgronaEventDispatcher selectShard(int eventType) {
        return shards[eventType & mask];
    }

    /**
     * Selects a shard using the session identifier hash.
     *
     * @param session the session associated with the operation
     * @return the selected shard
     */
    private AgronaEventDispatcher selectShardBySession(Session session) {
        int hash = session.getId().hashCode();
        return shards[hash & mask];
    }

    /**
     * Selects a shard using the current thread's random source.
     *
     * @return the selected shard
     */
    private AgronaEventDispatcher randomShard() {
        return shards[ThreadLocalRandom.current().nextInt(shards.length)];
    }

    // ==================== 公共方法 ====================

    /**
     * Selects the shard from the stable session identifier hash without transferring ownership.
     *
     * @param session the session associated with the operation
     * @return the shard selected for the session
     */
    public EventDispatcher getShardForSession(Session session) {
        return selectShardBySession(session);
    }

    /**
     * Tests shard membership by object identity.
     *
     * @param dispatcher the dispatcher
     * @return {@code true} if the dispatcher is one of this instance's shards
     */
    boolean containsShard(EventDispatcher dispatcher) {
        for (AgronaEventDispatcher shard : shards) {
            if (shard == dispatcher) {
                return true;
            }
        }
        return false;
    }

    // ==================== 监控方法 ====================

    /**
     * Returns the initialized shard count.
     *
     * @return the number of shards
     */
    public int shardCount() {
        return shards.length;
    }

    /**
     * Samples the sum of the shard queue depths.
     *
     * @return the approximate total queue depth
     */
    public int aggregateQueueSize() {
        int total = 0;
        for (AgronaEventDispatcher shard : shards) {
            total += shard.getQueueSize();
        }
        return total;
    }

    /**
     * Samples queue depths in shard-index order.
     *
     * @return a new array of approximate per-shard queue depths
     */
    public int[] shardQueueSizes() {
        int[] sizes = new int[shards.length];
        for (int i = 0; i < shards.length; i++) {
            sizes[i] = shards[i].getQueueSize();
        }
        return sizes;
    }
}
