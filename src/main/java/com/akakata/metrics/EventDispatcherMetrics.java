package com.akakata.metrics;

import com.akakata.event.impl.EventDispatchers;
import com.akakata.event.impl.ShardedEventDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Periodically samples pending Netty tasks on the process-wide session shards.
 *
 * <p>Running callbacks and the separately owned application Game queue are excluded.
 * Cancelled task shells can remain counted until their executor removes them.
 *
 * <p>A daemon-thread scheduler updates the singleton's cached values. A nonpositive
 * sampling interval disables scheduling. Stopping shuts down the scheduler; the
 * singleton is not designed to restart after shutdown.
 *
 * @author Kelvin
 */
public final class EventDispatcherMetrics {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(EventDispatcherMetrics.class);

    /**
     * Process-wide singleton instance.
     */
    private static final EventDispatcherMetrics INSTANCE = new EventDispatcherMetrics();

    // ============================================================
    // Fields
    // ============================================================

    /**
     * Daemon-thread scheduler for periodic metric sampling.
     */
    private final ScheduledExecutorService scheduler;

    /**
     * Atomic marker recording whether periodic sampling has been started.
     */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * Total queue size across all shards.
     * Updated by the monitoring task.
     */
    private final AtomicInteger totalQueueSize = new AtomicInteger(0);

    /**
     * Per-shard queue sizes.
     * Updated by the monitoring task.
     */
    private final AtomicReference<int[]> perShardQueueSizes = new AtomicReference<>(new int[0]);

    /**
     * Time of the latest successful sample in epoch milliseconds.
     */
    private volatile long lastUpdateTimestamp = 0;

    /**
     * Periodic task that updates cached shared-dispatcher queue metrics.
     */
    private final Runnable task = () -> {
        try {
            // Get total queue size
            int total = EventDispatchers.sharedQueueSize();

            // Get per-shard queue sizes
            int[] perShard = EventDispatchers.sharedShardQueueSizes();

            // Update stored values
            totalQueueSize.set(total);
            perShardQueueSizes.set(perShard);
            lastUpdateTimestamp = System.currentTimeMillis();
        } catch (Exception e) {
            LOG.error("Failed to collect event dispatcher metrics", e);
        }
    };

    // ============================================================
    // Constructor
    // ============================================================

    /**
     * Prevents direct construction of this utility or singleton component.
     */
    private EventDispatcherMetrics() {
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "event-dispatcher-metrics");
            t.setDaemon(true);  // Daemon thread, won't prevent JVM shutdown
            return t;
        };
        this.scheduler = new ScheduledThreadPoolExecutor(1, factory);
    }

    /**
     * Returns the process-wide singleton metrics collector.
     *
     * @return the singleton collector
     */
    public static EventDispatcherMetrics getInstance() {
        return INSTANCE;
    }

    // ============================================================
    // Lifecycle Methods
    // ============================================================

    /**
     * Schedules fixed-rate queue sampling once when the interval is positive.
     *
     * <p>A nonpositive interval disables sampling. Calling stop terminates the scheduler
     * permanently; started records whether scheduling has ever been requested successfully.
     *
     * @param intervalSeconds the sampling period in seconds; nonpositive values disable sampling
     */
    public void start(long intervalSeconds) {
        if (intervalSeconds <= 0) {
            LOG.info("Event dispatcher metrics disabled (intervalSeconds <= 0)");
            return;
        }

        // CAS ensures start can only succeed once
        if (started.compareAndSet(false, true)) {
            // Execute task at fixed rate
            scheduler.scheduleAtFixedRate(
                    task,
                    0,                  // Initial delay (collect immediately)
                    intervalSeconds,    // Collection interval
                    TimeUnit.SECONDS
            );

            LOG.info("Event dispatcher metrics collection every {} seconds with {} shards",
                    intervalSeconds, EventDispatchers.sharedShardCount());
        }
    }

    /**
     * Interrupts the metric scheduler and cancels pending sampling tasks.
     */
    public void stop() {
        scheduler.shutdownNow();
        LOG.info("Event dispatcher metrics stopped");
    }

    // ============================================================
    // Metric Getters
    // ============================================================

    /**
     * Get the total queue size across all shards.
     *
     * @return total queue size
     */
    public int getTotalQueueSize() {
        return totalQueueSize.get();
    }

    /**
     * Returns a defensive copy of the latest sampled queue depths.
     *
     * @return the cached per-shard queue depths
     */
    public int[] getPerShardQueueSizes() {
        int[] sizes = perShardQueueSizes.get();
        return sizes.clone(); // Return defensive copy
    }

    /**
     * Get the number of shards.
     *
     * @return shard count
     */
    public int getShardCount() {
        return EventDispatchers.sharedShardCount();
    }

    /**
     * Returns the time of the last successful sample in epoch milliseconds.
     *
     * @return the last sampling timestamp, or zero before any successful sample
     */
    public long getLastUpdateTimestamp() {
        return lastUpdateTimestamp;
    }

    /**
     * Tests whether periodic sampling has been started at least once.
     *
     * @return {@code true} if scheduling was started, including after stop
     */
    public boolean isStarted() {
        return started.get();
    }

    /**
     * Get the maximum queue size across all shards.
     * Useful for identifying hot shards.
     *
     * @return maximum shard queue size
     */
    public int getMaxShardQueueSize() {
        int[] sizes = perShardQueueSizes.get();
        int max = 0;
        for (int size : sizes) {
            if (size > max) {
                max = size;
            }
        }
        return max;
    }

    /**
     * Get the minimum queue size across all shards.
     * Useful for load balancing analysis.
     *
     * @return minimum shard queue size
     */
    public int getMinShardQueueSize() {
        int[] sizes = perShardQueueSizes.get();
        if (sizes.length == 0) return 0;

        int min = sizes[0];
        for (int size : sizes) {
            if (size < min) {
                min = size;
            }
        }
        return min;
    }

    /**
     * Returns the integer mean of cached shard depths.
     *
     * @return the truncated average queue depth, or zero before sampling
     */
    public int getAverageShardQueueSize() {
        int[] sizes = perShardQueueSizes.get();
        if (sizes.length == 0) return 0;

        int total = 0;
        for (int size : sizes) {
            total += size;
        }
        return total / sizes.length;
    }
}
