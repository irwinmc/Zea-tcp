package com.akakata.event.impl;

import com.akakata.app.Session;
import com.akakata.event.Event;
import com.akakata.event.EventDispatcher;
import com.akakata.event.EventHandler;
import com.akakata.event.Events;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 基于多分片的事件调度器，将事件分散到多个 {@link AgronaEventDispatcher}，
 * 以充分利用多核并降低单线程阻塞风险。
 */
public class ShardedEventDispatcher implements EventDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(ShardedEventDispatcher.class);

    private final AgronaEventDispatcher[] shards;
    private final int mask;

    public ShardedEventDispatcher(int shardCount) {
        if (Integer.bitCount(shardCount) != 1) {
            int nextPowerOfTwo = Integer.highestOneBit(shardCount - 1) << 1;
            LOG.info("Shard count {} is not power of two, rounding to {}", shardCount, nextPowerOfTwo);
            shardCount = nextPowerOfTwo;
        }
        this.shards = new AgronaEventDispatcher[shardCount];
        for (int i = 0; i < shardCount; i++) {
            shards[i] = new AgronaEventDispatcher();
            shards[i].initialize();
        }
        this.mask = shardCount - 1;
        LOG.info("Initialized ShardedEventDispatcher with {} shards", shardCount);
    }

    private AgronaEventDispatcher selectShard(int key) {
        return shards[key & mask];
    }

    private AgronaEventDispatcher randomShard() {
        return shards[ThreadLocalRandom.current().nextInt(shards.length)];
    }

    @Override
    public void addHandler(EventHandler eventHandler) {
        Objects.requireNonNull(eventHandler, "eventHandler");
        int eventType = eventHandler.getEventType();
        if (eventType == Events.ANY) {
            for (AgronaEventDispatcher shard : shards) {
                shard.addHandler(eventHandler);
            }
        } else {
            selectShard(eventType).addHandler(eventHandler);
        }
    }

    @Override
    public List<EventHandler> getHandlers(int eventType) {
        if (eventType == Events.ANY) {
            return randomShard().getHandlers(eventType);
        }
        return selectShard(eventType).getHandlers(eventType);
    }

    @Override
    public void removeHandler(EventHandler eventHandler) {
        if (eventHandler.getEventType() == Events.ANY) {
            for (AgronaEventDispatcher shard : shards) {
                shard.removeHandler(eventHandler);
            }
        } else {
            selectShard(eventHandler.getEventType()).removeHandler(eventHandler);
        }
    }

    @Override
    public void removeHandlersForEvent(int eventType) {
        if (eventType == Events.ANY) {
            for (AgronaEventDispatcher shard : shards) {
                shard.removeHandlersForEvent(eventType);
            }
        } else {
            selectShard(eventType).removeHandlersForEvent(eventType);
        }
    }

    @Override
    public boolean removeHandlersForSession(Session session) {
        boolean removed = false;
        for (AgronaEventDispatcher shard : shards) {
            removed |= shard.removeHandlersForSession(session);
        }
        return removed;
    }

    @Override
    public void clear() {
        for (AgronaEventDispatcher shard : shards) {
            shard.clear();
        }
    }

    @Override
    public void fireEvent(Event event) {
        if (event == null) {
            return;
        }
        selectShard(event.getType()).fireEvent(event);
    }

    @Override
    public void close() {
        for (AgronaEventDispatcher shard : shards) {
            shard.close();
        }
    }

    public int shardCount() {
        return shards.length;
    }

    public int aggregateQueueSize() {
        int total = 0;
        for (AgronaEventDispatcher shard : shards) {
            total += shard.getQueueSize();
        }
        return total;
    }

    public int[] shardQueueSizes() {
        int[] sizes = new int[shards.length];
        for (int i = 0; i < shards.length; i++) {
            sizes[i] = shards[i].getQueueSize();
        }
        return sizes;
    }
}
