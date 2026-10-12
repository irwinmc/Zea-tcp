package com.akakata.event.impl;

import com.akakata.app.Session;
import com.akakata.event.Event;
import com.akakata.event.EventDispatcher;
import com.akakata.event.EventHandler;
import com.akakata.event.SessionEventHandler;
import com.akakata.lifecycle.AsyncCloseable;
import com.akakata.lifecycle.ShutdownSupport;
import io.netty.util.concurrent.*;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns a bounded Netty business executor group and stable session shards.
 *
 * <p>Session deliveries use a stable identifier hash. Global subscriptions are
 * registered on every shard. Unscoped facade deliveries use the event-type hash.
 * All callbacks on one executor run serially in submission order.
 *
 * @since 0.8.0
 */
public class ShardedEventDispatcher implements EventDispatcher, AsyncCloseable {

    /**
     * Owner of all shared business threads and bounded task queues.
     */
    private final DefaultEventExecutorGroup executorGroup;

    /**
     * Session registration scopes indexed by the selection mask.
     */
    private final NettyEventDispatcher[] shards;

    /**
     * Mask for the power-of-two executor count.
     */
    private final int mask;

    /**
     * Completion signal shared by concurrent shutdown callers.
     */
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();

    /**
     * Terminal creation and registration flag; guarded by this facade.
     */
    private boolean closed;

    /**
     * Creates a group with the default maximum pending tasks per executor.
     *
     * @param shardCount positive shard count, rounded up to a power of two
     */
    public ShardedEventDispatcher(int shardCount) {
        this(shardCount, NettyEventDispatcher.DEFAULT_QUEUE_CAPACITY);
    }

    /**
     * Creates a lazily started business group with bounded pending tasks.
     *
     * @param shardCount    positive shard count, rounded up to a power of two
     * @param queueCapacity maximum pending tasks per executor, at least 16
     * @throws IllegalArgumentException if either value is outside its supported range
     */
    public ShardedEventDispatcher(int shardCount, int queueCapacity) {
        this(shardCount, queueCapacity, NettyEventDispatcher.DEFAULT_QUEUE_BYTES);
    }

    /**
     * Creates fixed session executors with independent count and byte budgets.
     *
     * @param shardCount    positive shard count rounded up to a power of two
     * @param queueCapacity maximum pending tasks per executor, at least 16
     * @param queueBytes    positive retained payload budget per executor
     * @throws IllegalArgumentException if a budget is invalid
     */
    public ShardedEventDispatcher(int shardCount, int queueCapacity, long queueBytes) {
        if (queueBytes <= 0) {
            throw new IllegalArgumentException("Positive payload budget required");
        }
        if (shardCount <= 0 || shardCount > (1 << 30) || queueCapacity < 16) {
            throw new IllegalArgumentException("Positive shard count and queue capacity of at least 16 required");
        }
        int count = Integer.bitCount(shardCount) == 1 ? shardCount : Integer.highestOneBit(shardCount - 1) << 1;
        this.mask = count - 1;
        this.executorGroup = new DefaultEventExecutorGroup(count,
                ShutdownSupport.workerFactory(new DefaultThreadFactory("zea-business", false)), queueCapacity, RejectedExecutionHandlers.reject());
        this.shards = new NettyEventDispatcher[count];
        for (int i = 0; i < count; i++) {
            shards[i] = new NettyEventDispatcher(executorGroup.next(), queueCapacity, queueBytes);
        }
        executorGroup.terminationFuture().addListener(future -> {
            if (future.isSuccess()) {
                ShutdownSupport.complete(closeCompletion, null);
            } else {
                ShutdownSupport.complete(closeCompletion, future.cause());
            }
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void addHandler(EventHandler handler) {
        Objects.requireNonNull(handler, "handler");
        if (closed) {
            throw new IllegalStateException("Dispatcher is closed");
        }
        if (handler instanceof SessionEventHandler scoped && scoped.getSession() != null) {
            selectSession(scoped.getSession()).addHandler(handler);
        } else {
            for (NettyEventDispatcher shard : shards) {
                shard.addHandler(handler);
            }
        }
    }

    /**
     * Returns an aggregate snapshot, deduplicating global handlers by identity.
     *
     * @param eventType the event subscription type
     * @return handlers registered for this type on any session shard
     */
    @Override
    public List<EventHandler> getHandlers(int eventType) {
        Set<EventHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<EventHandler> result = new ArrayList<>();
        for (NettyEventDispatcher shard : shards) {
            for (EventHandler handler : shard.getHandlers(eventType)) {
                if (seen.add(handler)) {
                    result.add(handler);
                }
            }
        }
        return result;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void removeHandler(EventHandler handler) {
        for (NettyEventDispatcher shard : shards) {
            shard.removeHandler(handler);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void removeHandlersForEvent(int eventType) {
        for (NettyEventDispatcher shard : shards) {
            shard.removeHandlersForEvent(eventType);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeHandlersForSession(Session session) {
        return selectSession(Objects.requireNonNull(session, "session")).removeHandlersForSession(session);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void clear() {
        for (NettyEventDispatcher shard : shards) {
            shard.clear();
        }
    }

    /**
     * Attempts an unscoped publication to the opcode-selected executor.
     *
     * @param event the event to publish
     * @return whether the selected shard accepted the event
     */
    @Override
    public boolean fireEvent(Event event) {
        return event != null && shards[event.getType() & mask].fireEvent(event);
    }

    /**
     * Attempts publication to the target session's stable executor.
     *
     * @param event   the event to publish
     * @param session the target session
     * @return whether the target shard accepted the event
     */
    @Override
    public boolean fireEvent(Event event, Session session) {
        return event != null && selectSession(Objects.requireNonNull(session, "session")).fireEvent(event, session);
    }

    /**
     * Selects a stable shard from the immutable session identifier.
     *
     * @param session the target session
     * @return the session's fixed registration and execution scope
     */
    private NettyEventDispatcher selectSession(Session session) {
        return shards[session.getId().hashCode() & mask];
    }

    /**
     * Returns a borrowed session shard without transferring ownership.
     *
     * @param session the target session
     * @return the shard selected by its immutable identifier
     */
    public EventDispatcher getShardForSession(Session session) {
        return selectSession(Objects.requireNonNull(session, "session"));
    }

    /**
     * Tests whether this facade owns the supplied session shard.
     *
     * @param dispatcher the potential shard
     * @return true for an owned session registration scope
     */
    boolean containsShard(EventDispatcher dispatcher) {
        for (NettyEventDispatcher shard : shards) {
            if (shard == dispatcher) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        closeAsync();
        if (!NettyEventDispatcher.isDispatchThread() && !inEventLoop()) {
            ShutdownSupport.await(closeCompletion, ShutdownSupport.DEFAULT_TIMEOUT_MILLIS, "session dispatcher group");
        }
    }

    /**
     * Stops every shard before requesting owned executor termination.
     *
     * @return an independent observation of actual executor termination
     */
    @Override
    public CompletableFuture<Void> closeAsync() {
        List<NettyEventDispatcher> scopes = null;
        synchronized (this) {
            if (!closed) {
                closed = true;
                scopes = List.of(shards);
            }
        }
        if (scopes != null) {
            try {
                for (NettyEventDispatcher scope : scopes) {
                    scope.beginClose();
                }
            } finally {
                executorGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            }
        }
        return closeCompletion.copy();
    }

    /**
     * Checks availability of every session shard without adding probe traffic.
     *
     * @param maximumDelayMillis maximum queued or active event age
     * @return whether every shard is healthy
     * @throws IllegalArgumentException if the delay is not positive
     */
    @Override
    public synchronized boolean isHealthy(long maximumDelayMillis) {
        if (maximumDelayMillis <= 0) {
            throw new IllegalArgumentException("Health event delay must be positive");
        }
        if (closed) {
            return false;
        }
        for (NettyEventDispatcher shard : shards) {
            if (!shard.isHealthy(maximumDelayMillis)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tests whether this caller runs on any executor owned by this group.
     *
     * @return true if waiting for group termination would join the caller
     */
    private boolean inEventLoop() {
        for (EventExecutor executor : executorGroup) {
            if (executor.inEventLoop()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the fixed executor count.
     *
     * @return the number of session shards and business executors
     */
    public int shardCount() {
        return shards.length;
    }

    /**
     * Samples pending Netty tasks across the session shards.
     *
     * @return approximate total pending tasks, excluding running callbacks
     */
    public int aggregateQueueSize() {
        int total = 0;
        for (int size : shardQueueSizes()) {
            total += size;
        }
        return total;
    }

    /**
     * Samples physical executor queues in shard-index order.
     *
     * @return approximate pending tasks per executor, excluding running callbacks
     */
    public int[] shardQueueSizes() {
        int[] sizes = new int[shards.length];
        for (int i = 0; i < shards.length; i++) {
            sizes[i] = ((SingleThreadEventExecutor) shards[i].executor()).pendingTasks();
        }
        return sizes;
    }

    /**
     * Captures flow metrics for each fixed session executor.
     *
     * @return immutable snapshots in shard-index order
     */
    public List<DispatcherSnapshot> snapshots() {
        return java.util.Arrays.stream(shards).map(NettyEventDispatcher::snapshot).toList();
    }

}
