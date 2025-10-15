package com.akakata.event.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 周期性输出事件调度分片的队列长度，便于压测时观察热点分布。
 */
public final class EventDispatcherMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(EventDispatcherMetrics.class);

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Runnable task = () -> {
        int total = EventDispatchers.sharedQueueSize();
        int[] perShard = EventDispatchers.sharedShardQueueSizes();
        LOG.info("EventDispatcher queue size total={}, perShard={}", total, Arrays.toString(perShard));
    };

    public EventDispatcherMetrics() {
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "event-dispatcher-metrics");
            t.setDaemon(true);
            return t;
        };
        this.scheduler = new ScheduledThreadPoolExecutor(1, factory);
    }

    public void start(long intervalSeconds) {
        if (intervalSeconds <= 0) {
            LOG.info("Event dispatcher metrics disabled (intervalSeconds <= 0)");
            return;
        }
        if (started.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(task, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
            LOG.info("Event dispatcher metrics reporting every {} seconds with {} shards",
                    intervalSeconds, EventDispatchers.sharedShardCount());
        }
    }

    public void stop() {
        scheduler.shutdownNow();
    }
}
