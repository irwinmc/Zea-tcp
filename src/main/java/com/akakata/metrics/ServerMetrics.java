package com.akakata.metrics;

import com.akakata.server.impl.AbstractNettyServer;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects connection, traffic, error, uptime and JVM resource metrics.
 *
 * <p>Counters use atomic values and represent explicit calls from instrumented
 * code. System values are sampled when requested rather than continuously collected.
 *
 * @author Kelvin
 */
public class ServerMetrics {

    /**
     * Process-wide singleton instance.
     */
    private static final ServerMetrics INSTANCE = new ServerMetrics();

    // Connection metrics
    /**
     * Cumulative number of recorded connection openings.
     */
    private final AtomicLong totalConnections = new AtomicLong(0);
    /**
     * Recorded connection openings minus recorded disconnections.
     */
    private final AtomicLong activeConnections = new AtomicLong(0);
    /**
     * Cumulative number of recorded inbound messages.
     */
    private final AtomicLong totalMessagesReceived = new AtomicLong(0);
    /**
     * Cumulative number of recorded outbound messages.
     */
    private final AtomicLong totalMessagesSent = new AtomicLong(0);
    /**
     * Cumulative number of recorded inbound bytes.
     */
    private final AtomicLong totalBytesReceived = new AtomicLong(0);
    /**
     * Cumulative number of recorded outbound bytes.
     */
    private final AtomicLong totalBytesSent = new AtomicLong(0);

    // Error metrics
    /**
     * Cumulative number of recorded errors.
     */
    private final AtomicLong totalErrors = new AtomicLong(0);

    // Server start time
    /**
     * Collector creation time in epoch milliseconds.
     */
    private final long startTime = System.currentTimeMillis();

    /**
     * Prevents direct construction of this utility or singleton component.
     */
    private ServerMetrics() {
        // Private constructor for singleton
    }

    /**
     * Returns the process-wide singleton metrics collector.
     *
     * @return the singleton collector
     */
    public static ServerMetrics getInstance() {
        return INSTANCE;
    }

    // ============================================================
    // Connection Metrics
    // ============================================================

    /**
     * Records connection in the cumulative metrics.
     */
    public void recordConnection() {
        totalConnections.incrementAndGet();
        activeConnections.incrementAndGet();
    }

    /**
     * Records disconnection in the cumulative metrics.
     */
    public void recordDisconnection() {
        activeConnections.decrementAndGet();
    }

    /**
     * Records message received in the cumulative metrics.
     */
    public void recordMessageReceived() {
        totalMessagesReceived.incrementAndGet();
    }

    /**
     * Records message sent in the cumulative metrics.
     */
    public void recordMessageSent() {
        totalMessagesSent.incrementAndGet();
    }

    /**
     * Records bytes received in the cumulative metrics.
     *
     * @param bytes the number of bytes to add to the counter
     */
    public void recordBytesReceived(long bytes) {
        totalBytesReceived.addAndGet(bytes);
    }

    /**
     * Records bytes sent in the cumulative metrics.
     *
     * @param bytes the number of bytes to add to the counter
     */
    public void recordBytesSent(long bytes) {
        totalBytesSent.addAndGet(bytes);
    }

    /**
     * Records error in the cumulative metrics.
     */
    public void recordError() {
        totalErrors.incrementAndGet();
    }

    // ============================================================
    // Getters
    // ============================================================

    /**
     * Returns the cumulative recorded connection count.
     *
     * @return the cumulative recorded connection count
     */
    public long getTotalConnections() {
        return totalConnections.get();
    }

    /**
     * Returns the recorded active connection count.
     *
     * @return the recorded active connection count
     */
    public long getActiveConnections() {
        return activeConnections.get();
    }

    /**
     * Returns the size of the shared server channel group. This count includes whichever channels
     * callers registered.
     *
     * @return the current registered-channel count
     */
    public int getCurrentChannelCount() {
        return AbstractNettyServer.ALL_CHANNELS.size();
    }

    /**
     * Returns the cumulative recorded inbound message count.
     *
     * @return the cumulative recorded inbound message count
     */
    public long getTotalMessagesReceived() {
        return totalMessagesReceived.get();
    }

    /**
     * Returns the cumulative recorded outbound message count.
     *
     * @return the cumulative recorded outbound message count
     */
    public long getTotalMessagesSent() {
        return totalMessagesSent.get();
    }

    /**
     * Returns the cumulative recorded inbound byte count.
     *
     * @return the cumulative recorded inbound byte count
     */
    public long getTotalBytesReceived() {
        return totalBytesReceived.get();
    }

    /**
     * Returns the cumulative recorded outbound byte count.
     *
     * @return the cumulative recorded outbound byte count
     */
    public long getTotalBytesSent() {
        return totalBytesSent.get();
    }

    /**
     * Returns the cumulative recorded error count.
     *
     * @return the cumulative recorded error count
     */
    public long getTotalErrors() {
        return totalErrors.get();
    }

    /**
     * Returns elapsed time since this collector was created.
     *
     * @return the uptime in milliseconds
     */
    public long getUptimeMillis() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * Returns elapsed time since this collector was created, truncated to whole seconds.
     *
     * @return the uptime in seconds
     */
    public long getUptimeSeconds() {
        return getUptimeMillis() / 1000;
    }

    // ============================================================
    // System Metrics
    // ============================================================

    /**
     * Samples used JVM heap memory in mebibytes.
     *
     * @return used heap bytes divided by 1,048,576
     */
    public long getUsedMemoryMB() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        return memoryBean.getHeapMemoryUsage().getUsed() / 1024 / 1024;
    }

    /**
     * Returns the JVM maximum heap size in mebibytes.
     *
     * @return maximum heap bytes divided by 1,048,576
     */
    public long getMaxMemoryMB() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        return memoryBean.getHeapMemoryUsage().getMax() / 1024 / 1024;
    }

    /**
     * Returns the current JVM thread count.
     *
     * @return the current JVM thread count
     */
    public int getThreadCount() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        return threadBean.getThreadCount();
    }

    /**
     * Samples system CPU utilization through the extended operating-system management bean.
     *
     * @return the load between 0 and 1, or a negative value when unavailable
     * @throws ClassCastException if the JVM does not expose the extended operating-system bean
     */
    public double getCpuLoad() {
        // Return system CPU load (0.0 to 1.0)
        com.sun.management.OperatingSystemMXBean osBean =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        return osBean.getCpuLoad();
    }

    // ============================================================
    // Reset (for testing)
    // ============================================================

    /**
     * Resets connection, traffic and error counters. The collector creation time is unchanged.
     */
    public void reset() {
        totalConnections.set(0);
        activeConnections.set(0);
        totalMessagesReceived.set(0);
        totalMessagesSent.set(0);
        totalBytesReceived.set(0);
        totalBytesSent.set(0);
        totalErrors.set(0);
    }
}
