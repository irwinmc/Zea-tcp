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
 * Event dispatcher metrics collector.
 * <p>
 * Periodically collects {@link ShardedEventDispatcher} queue statistics for monitoring and analysis.
 * Tracks:
 * <ul>
 *   <li>Total queue backlog (sum of all shard queues)</li>
 *   <li>Per-shard queue sizes (for load balancing observation)</li>
 *   <li>Hot shard identification (whether any shard is overloaded)</li>
 * </ul>
 * <p>
 * <b>Usage:</b>
 * <pre>{@code
 * EventDispatcherMetrics metrics = EventDispatcherMetrics.getInstance();
 *
 * // Start periodic collection (every 5 seconds)
 * metrics.start(5);
 *
 * // Access metrics via HTTP endpoint /metrics/event-dispatcher
 *
 * // Stop monitoring
 * metrics.stop();
 * }</pre>
 *
 * @author Kelvin
 */
public final class EventDispatcherMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(EventDispatcherMetrics.class);
    private static final EventDispatcherMetrics INSTANCE = new EventDispatcherMetrics();

    // ============================================================
    // Fields
    // ============================================================

    /**
     * Scheduled task executor.
     * Single-threaded scheduler for periodic metric collection.
     * Thread is daemon to not prevent JVM shutdown.
     */
    private final ScheduledExecutorService scheduler;

    /**
     * Start state flag.
     * Ensures start() can only be called successfully once.
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
     * Last update timestamp.
     */
    private volatile long lastUpdateTimestamp = 0;

    /**
     * Monitoring task.
     * Periodically collects and stores EventDispatcher queue state.
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
     * Private constructor for singleton pattern.
     * Creates a single-threaded scheduled executor with daemon thread.
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
     * Get the singleton instance.
     *
     * @return EventDispatcherMetrics instance
     */
    public static EventDispatcherMetrics getInstance() {
        return INSTANCE;
    }

    // ============================================================
    // Lifecycle Methods
    // ============================================================

    /**
     * Start periodic metric collection.
     * <p>
     * This method can only be called successfully once. Repeated calls are ignored.
     * <p>
     * <b>Notes:</b>
     * <ul>
     *   <li>If intervalSeconds <= 0, monitoring is disabled</li>
     *   <li>First collection happens after intervalSeconds</li>
     *   <li>Subsequent collections happen every intervalSeconds</li>
     * </ul>
     *
     * @param intervalSeconds collection interval in seconds (must be > 0 to enable)
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
     * Stop metric collection.
     * <p>
     * Shuts down the scheduler immediately, stopping all monitoring tasks.
     * If a task is running, it will be interrupted.
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
     * Get per-shard queue sizes.
     *
     * @return array of queue sizes per shard (defensive copy)
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
     * Get the timestamp of the last metric update.
     *
     * @return last update timestamp in milliseconds, or 0 if never updated
     */
    public long getLastUpdateTimestamp() {
        return lastUpdateTimestamp;
    }

    /**
     * Check if the monitoring is currently running.
     *
     * @return true if started, false otherwise
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
     * Get the average queue size per shard.
     *
     * @return average shard queue size (rounded down)
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
