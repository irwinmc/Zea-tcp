package com.akakata.event.impl;

import com.akakata.app.Session;
import com.akakata.event.EventDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 事件调度器工厂，集中维护共享的 Agrona 事件总线，避免为每个会话创建线程。
 *
 * @author Kelvin
 */
public final class EventDispatchers {

    private static final Logger LOG = LoggerFactory.getLogger(EventDispatchers.class);

    private static final ShardedEventDispatcher SHARED_DISPATCHER = createSharedDispatcher();
    private static final AtomicBoolean SHARED_CLOSED = new AtomicBoolean(false);

    private EventDispatchers() {
    }

    private static ShardedEventDispatcher createSharedDispatcher() {
        int shardCount = Math.max(2, Integer.highestOneBit(Runtime.getRuntime().availableProcessors()));
        ShardedEventDispatcher dispatcher = new ShardedEventDispatcher(shardCount);
        LOG.info("Initialized shared ShardedEventDispatcher with {} shards", dispatcher.shardCount());
        return dispatcher;
    }

    /**
     * 默认返回进程级共享调度器。
     */
    public static EventDispatcher sharedDispatcher() {
        return SHARED_DISPATCHER;
    }

    /**
     * 如业务确需独立线程，可主动创建专用调度器。
     */
    public static EventDispatcher newDedicatedDispatcher() {
        return new ShardedEventDispatcher(2);
    }

    /**
     * Session 释放时调用，共享调度器仅移除该 session 的 handlers，专用调度器则完全关闭。
     */
    public static void release(EventDispatcher dispatcher, Session session) {
        if (dispatcher == null) {
            return;
        }
        if (dispatcher == SHARED_DISPATCHER) {
            dispatcher.removeHandlersForSession(session);
        } else {
            dispatcher.close();
        }
    }

    /**
     * 在服务器整体关闭时调用，释放共享调度器资源。
     */
    public static void shutdownSharedDispatcher() {
        if (SHARED_CLOSED.compareAndSet(false, true)) {
            LOG.info("Shutting down shared ShardedEventDispatcher");
            SHARED_DISPATCHER.close();
        }
    }

    /**
     * 当前共享调度器队列总长度，便于监控或调试。
     */
    public static int sharedQueueSize() {
        return SHARED_DISPATCHER.aggregateQueueSize();
    }

    public static int sharedShardCount() {
        return SHARED_DISPATCHER.shardCount();
    }

    public static int[] sharedShardQueueSizes() {
        return SHARED_DISPATCHER.shardQueueSizes();
    }
}
