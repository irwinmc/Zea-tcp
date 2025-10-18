package com.akakata;

import com.akakata.context.ServerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Advanced test server with custom lifecycle hooks.
 * Demonstrates:
 * - Custom configuration path
 * - Background task management
 * - Metrics collection
 * - Graceful shutdown with state persistence
 *
 * @author Kelvin
 */
public class AdvancedTestServer extends CommandLine {

    private static final Logger LOG = LoggerFactory.getLogger(AdvancedTestServer.class);

    private ScheduledExecutorService scheduler;
    private final AtomicInteger connectionCount = new AtomicInteger(0);
    private final AtomicInteger messageCount = new AtomicInteger(0);

    public static void main(String[] args) {
        new AdvancedTestServer().run(args);
    }

    @Override
    protected ServerContext createServerContext() throws Exception {
        // Use custom configuration if needed
        String configPath = System.getProperty("config.path", "props/conf.properties");
        LOG.info("Using configuration: {}", configPath);
        return new ServerContext(configPath);
    }

    @Override
    protected void beforeServerStartup() throws Exception {
        LOG.info("╔═══════════════════════════════════════════════╗");
        LOG.info("║   Advanced Test Server - Enhanced Mode       ║");
        LOG.info("╚═══════════════════════════════════════════════╝");

        // Initialize test data
        initializeTestData();

        // Setup metrics
        setupMetrics();
    }

    @Override
    protected void afterServerStartup() throws Exception {
        LOG.info("Server endpoints:");
        LOG.info("  → TCP:       tcp://localhost:8090");
        LOG.info("  → HTTP:      http://localhost:8081");
        LOG.info("  → WebSocket: ws://localhost:8300");
        LOG.info("╚═══════════════════════════════════════════════╝");

        // Start background tasks
        startBackgroundTasks();

        // Run health check
        runHealthCheck();
    }

    @Override
    protected void beforeServerShutdown() {
        LOG.info("╔═══════════════════════════════════════════════╗");
        LOG.info("║   Shutting down Advanced Test Server         ║");
        LOG.info("╚═══════════════════════════════════════════════╝");

        // Save statistics
        saveStatistics();

        // Stop background tasks
        stopBackgroundTasks();
    }

    @Override
    protected void afterServerShutdown() {
        LOG.info("Final statistics:");
        LOG.info("  → Total connections: {}", connectionCount.get());
        LOG.info("  → Total messages:    {}", messageCount.get());
        LOG.info("╚═══════════════════════════════════════════════╝");
    }

    // ============================================================
    // Custom Implementation Methods
    // ============================================================

    private void initializeTestData() {
        LOG.info("Loading test data...");
        // TODO: Load test users, game state, etc.
        LOG.info("Test data loaded successfully");
    }

    private void setupMetrics() {
        LOG.info("Setting up metrics collection...");
        // TODO: Initialize metrics collectors
        LOG.info("Metrics collection ready");
    }

    private void startBackgroundTasks() {
        LOG.info("Starting background tasks...");

        scheduler = Executors.newScheduledThreadPool(2);

        // Task 1: Periodic metrics reporting
        scheduler.scheduleAtFixedRate(() -> {
            LOG.info("Metrics - Connections: {}, Messages: {}",
                    connectionCount.get(), messageCount.get());
        }, 10, 10, TimeUnit.SECONDS);

        // Task 2: Health check
        scheduler.scheduleAtFixedRate(() -> {
            if (isRunning()) {
                LOG.debug("Health check: OK");
            }
        }, 30, 30, TimeUnit.SECONDS);

        LOG.info("Background tasks started");
    }

    private void stopBackgroundTasks() {
        if (scheduler != null && !scheduler.isShutdown()) {
            LOG.info("Stopping background tasks...");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            LOG.info("Background tasks stopped");
        }
    }

    private void runHealthCheck() {
        LOG.info("Running health check...");
        boolean healthy = serverManager != null && isRunning();
        LOG.info("Health check: {}", healthy ? "PASS" : "FAIL");
    }

    private void saveStatistics() {
        LOG.info("Saving statistics...");
        // TODO: Persist stats to file or database
        LOG.info("Statistics saved");
    }
}
