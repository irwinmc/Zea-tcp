package com.akakata.event.impl;

import com.akakata.app.Session;
import com.akakata.event.EventDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provides process-wide shared dispatchers, dedicated dispatchers and ownership-aware cleanup.
 *
 * <p>Shared dispatchers are created on first use and are shut down
 * once for the process. Closing an individual session removes only its handlers
 * from a shared dispatcher or shared shard.
 *
 * @author Kelvin
 */
public final class EventDispatchers {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(EventDispatchers.class);

    // ==================== 静态字段 ====================

    /**
     * Process-wide sharded dispatcher created on configuration or the first shared access.
     */
    private static volatile ShardedEventDispatcher shared;

    /**
     * Atomic guard ensuring shared-dispatcher shutdown runs once.
     */
    private static final AtomicBoolean SHARED_CLOSED = new AtomicBoolean(false);

    // ==================== 构造函数 ====================

    /**
     * Prevents direct construction of this utility or singleton component.
     */
    private EventDispatchers() {
    }

    // ==================== 初始化方法 ====================

    /**
     * Creates at least two lazily started shards, sized from the available processors.
     *
     * @return the shared dispatcher
     */
    private static ShardedEventDispatcher createSharedDispatcher() {
        int cores = Runtime.getRuntime().availableProcessors();
        int shardCount = Math.max(2, Integer.highestOneBit(cores));

        ShardedEventDispatcher dispatcher = new ShardedEventDispatcher(shardCount);

        LOG.info("Initialized shared ShardedEventDispatcher with {} shards (CPU cores: {})",
                dispatcher.shardCount(), cores);

        return dispatcher;
    }

    /**
     * Initializes process-wide session budgets before the first session is created.
     *
     * <p>An existing group cannot be resized; conflicting configurations fail rather
     * than silently changing live session ordering or resource ownership.
     *
     * @param shards positive fixed executor count, rounded up to a power of two
     * @param capacity pending tasks per executor, at least 16
     * @param bytes positive payload budget per executor
     * @throws IllegalArgumentException if a count or byte budget is invalid
     * @throws IllegalStateException if the live group has different budgets or is closed
     */
    public static synchronized void configure(int shards, int capacity, long bytes) {
        if (shards <= 0 || shards > (1 << 30) || capacity < 16 || bytes <= 0)
            throw new IllegalArgumentException("Invalid shared dispatcher budgets");
        if (SHARED_CLOSED.get()) throw new IllegalStateException("Shared dispatchers have been closed");
        int count = shards > 0 && Integer.bitCount(shards) == 1 ? shards : Integer.highestOneBit(shards - 1) << 1;
        if (shared != null) {
            var existing = shared.snapshots().getFirst();
            if (shared.shardCount() != count || existing.capacity() != capacity || existing.byteBudget() != bytes)
                throw new IllegalStateException("Shared dispatcher configuration already fixed");
        } else shared = new ShardedEventDispatcher(shards, capacity, bytes);
    }

    /**
     * Returns the lazily created process owner without replacing terminated resources.
     *
     * @return the shared session executor group
     */
    public static synchronized ShardedEventDispatcher sharedGroup() {
        if (shared == null) shared = createSharedDispatcher();
        return shared;
    }

    // ==================== 获取调度器方法 ====================

    /**
     * Returns the process-wide shared sharded dispatcher. It is not recreated after shutdown.
     *
     * @return the shared dispatcher
     */
    public static EventDispatcher sharedDispatcher() {
        return sharedGroup();
    }

    /**
     * Selects the shard from the stable session identifier hash without transferring ownership.
     *
     * @param session the session associated with the operation
     * @return the shard selected for the session
     */
    public static EventDispatcher getShardForSession(Session session) {
        return sharedGroup().getShardForSession(session);
    }

    /**
     * Creates a lazily started two-shard dispatcher owned by the caller.
     *
     * @return a new dedicated dispatcher that the caller must close
     */
    public static EventDispatcher newDedicatedDispatcher() {
        return new ShardedEventDispatcher(2);
    }

    // ==================== 资源管理方法 ====================

    /**
     * Releases session registrations from a shared dispatcher or shared shard; closes a dedicated
     * dispatcher.
     *
     * <p>A null dispatcher is ignored. Other sessions and global handlers on shared
     * dispatchers remain registered.
     *
     * @param dispatcher the dispatcher
     * @param session the session associated with the operation
     */
    public static void release(EventDispatcher dispatcher, Session session) {
        if (dispatcher == null) {
            return;
        }

        if (dispatcher == shared || (shared != null && shared.containsShard(dispatcher))
                || (dispatcher instanceof NettyEventDispatcher netty && netty.borrowsExecutor())) {
            // 共享调度器及其分片：只移除该 session 的 handlers
            dispatcher.removeHandlersForSession(session);
            LOG.debug("Removed handlers for session {} from shared dispatcher", session.getId());
        } else {
            // 专用调度器：完全关闭
            dispatcher.close();
            LOG.debug("Closed dedicated dispatcher for session {}", session.getId());
        }
    }

    /**
     * Closes the process-wide shared dispatcher once. Subsequent calls have no effect.
     */
    public static void shutdownSharedDispatcher() {
        if (SHARED_CLOSED.compareAndSet(false, true)) {
            LOG.info("Shutting down shared ShardedEventDispatcher with {} shards",
                    sharedGroup().shardCount());
            sharedGroup().close();
        }
    }

    // ==================== 监控方法 ====================

    /**
     * Samples the total number of pending deliveries across shared shards.
     *
     * @return the approximate total queue depth
     */
    public static int sharedQueueSize() {
        return sharedGroup().aggregateQueueSize();
    }

    /**
     * Returns the number of shared dispatcher shards.
     *
     * @return the shared shard count
     */
    public static int sharedShardCount() {
        return sharedGroup().shardCount();
    }

    /**
     * Samples the queue depth of each shared shard.
     *
     * @return a new array of approximate per-shard queue depths
     */
    public static int[] sharedShardQueueSizes() {
        return sharedGroup().shardQueueSizes();
    }
}
