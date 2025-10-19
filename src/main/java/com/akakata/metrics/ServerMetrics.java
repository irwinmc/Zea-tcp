package com.akakata.metrics;

import com.akakata.server.impl.AbstractNettyServer;
import io.netty.channel.Channel;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server metrics collector for monitoring server health and performance.
 * Thread-safe singleton pattern.
 *
 * @author Kelvin
 */
public class ServerMetrics {

    private static final ServerMetrics INSTANCE = new ServerMetrics();

    // Connection metrics
    private final AtomicLong totalConnections = new AtomicLong(0);
    private final AtomicLong activeConnections = new AtomicLong(0);
    private final AtomicLong totalMessagesReceived = new AtomicLong(0);
    private final AtomicLong totalMessagesSent = new AtomicLong(0);
    private final AtomicLong totalBytesReceived = new AtomicLong(0);
    private final AtomicLong totalBytesSent = new AtomicLong(0);

    // Error metrics
    private final AtomicLong totalErrors = new AtomicLong(0);

    // Server start time
    private final long startTime = System.currentTimeMillis();

    private ServerMetrics() {
        // Private constructor for singleton
    }

    public static ServerMetrics getInstance() {
        return INSTANCE;
    }

    // ============================================================
    // Connection Metrics
    // ============================================================

    public void recordConnection() {
        totalConnections.incrementAndGet();
        activeConnections.incrementAndGet();
    }

    public void recordDisconnection() {
        activeConnections.decrementAndGet();
    }

    public void recordMessageReceived() {
        totalMessagesReceived.incrementAndGet();
    }

    public void recordMessageSent() {
        totalMessagesSent.incrementAndGet();
    }

    public void recordBytesReceived(long bytes) {
        totalBytesReceived.addAndGet(bytes);
    }

    public void recordBytesSent(long bytes) {
        totalBytesSent.addAndGet(bytes);
    }

    public void recordError() {
        totalErrors.incrementAndGet();
    }

    // ============================================================
    // Getters
    // ============================================================

    public long getTotalConnections() {
        return totalConnections.get();
    }

    public long getActiveConnections() {
        return activeConnections.get();
    }

    public int getCurrentChannelCount() {
        return AbstractNettyServer.ALL_CHANNELS.size();
    }

    public long getTotalMessagesReceived() {
        return totalMessagesReceived.get();
    }

    public long getTotalMessagesSent() {
        return totalMessagesSent.get();
    }

    public long getTotalBytesReceived() {
        return totalBytesReceived.get();
    }

    public long getTotalBytesSent() {
        return totalBytesSent.get();
    }

    public long getTotalErrors() {
        return totalErrors.get();
    }

    public long getUptimeMillis() {
        return System.currentTimeMillis() - startTime;
    }

    public long getUptimeSeconds() {
        return getUptimeMillis() / 1000;
    }

    // ============================================================
    // System Metrics
    // ============================================================

    public long getUsedMemoryMB() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        return memoryBean.getHeapMemoryUsage().getUsed() / 1024 / 1024;
    }

    public long getMaxMemoryMB() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        return memoryBean.getHeapMemoryUsage().getMax() / 1024 / 1024;
    }

    public int getThreadCount() {
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        return threadBean.getThreadCount();
    }

    public double getCpuLoad() {
        // Return system CPU load (0.0 to 1.0)
        com.sun.management.OperatingSystemMXBean osBean =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        return osBean.getSystemCpuLoad();
    }

    // ============================================================
    // Reset (for testing)
    // ============================================================

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
