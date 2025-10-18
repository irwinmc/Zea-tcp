package com.akakata;

import com.akakata.context.ServerContext;
import com.akakata.server.ServerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for launching Zea-tcp game server.
 * Provides lifecycle hooks for custom initialization and cleanup.
 *
 * <p>Subclasses should override the hook methods to add custom behavior:
 * <ul>
 *   <li>{@link #beforeServerStartup()} - Initialize resources before servers start</li>
 *   <li>{@link #afterServerStartup()} - Post-startup tasks (e.g., background jobs)</li>
 *   <li>{@link #beforeServerShutdown()} - Pre-shutdown cleanup (e.g., save state)</li>
 *   <li>{@link #afterServerShutdown()} - Final cleanup (e.g., close connections)</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * public class GameServer extends CommandLine {
 *     public static void main(String[] args) {
 *         new GameServer().run(args);
 *     }
 *
 *     @Override
 *     protected void beforeServerStartup() {
 *         // Load game data
 *     }
 * }
 * }</pre>
 *
 * @author Kelvin
 */
public abstract class CommandLine {

    private static final Logger LOG = LoggerFactory.getLogger(CommandLine.class);

    protected ServerContext context;
    protected ServerManager serverManager;
    private volatile boolean running = false;

    /**
     * Run the server with the given command line arguments.
     *
     * @param args command line arguments
     */
    public final void run(String[] args) {
        LOG.info("Starting Zea-tcp server...");

        try {
            // Startup sequence
            start();

            // Register shutdown hook
            registerShutdownHook();

            LOG.info("Zea-tcp server started successfully. Press Ctrl+C to stop.");

            // Keep main thread alive
            awaitTermination();

        } catch (Exception e) {
            LOG.error("Failed to start Zea-tcp server", e);
            shutdown();
            System.exit(1);
        }
    }

    /**
     * Startup sequence: initialize context and start all servers.
     * This method is final to ensure consistent startup flow.
     */
    private void start() throws Exception {
        // 1. Initialize context
        context = createServerContext();
        context.initialize();
        LOG.info("ServerContext initialized successfully");

        // 2. Hook: Before server startup (add custom initialization here)
        beforeServerStartup();

        // 3. Start all servers
        serverManager = context.getBean(ServerManager.class);
        serverManager.startServers();
        LOG.info("All servers started successfully");

        running = true;

        // 4. Hook: After server startup (add custom post-startup logic here)
        afterServerStartup();
    }

    /**
     * Shutdown sequence: stop servers and clean up resources.
     * This method is final to ensure consistent shutdown flow.
     */
    private void shutdown() {
        if (!running) {
            return;
        }

        LOG.info("Shutting down Zea-tcp server...");

        // 1. Hook: Before server shutdown (add custom cleanup here)
        beforeServerShutdown();

        // 2. Stop all servers
        if (serverManager != null) {
            try {
                serverManager.stopServers();
                LOG.info("All servers stopped successfully");
            } catch (Exception e) {
                LOG.error("Error stopping servers", e);
            }
        }

        // 3. Hook: After server shutdown (add custom cleanup here)
        afterServerShutdown();

        // 4. Close context
        if (context != null) {
            context.close();
        }

        running = false;
        LOG.info("Zea-tcp server stopped");
    }

    /**
     * Create the ServerContext instance.
     * Override this method to provide a custom configuration path.
     *
     * @return ServerContext instance
     * @throws Exception if context creation fails
     */
    protected ServerContext createServerContext() throws Exception {
        return new ServerContext();
    }

    /**
     * Register shutdown hook for graceful termination.
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutdown signal received");
            shutdown();
        }, "shutdown-hook"));
    }

    /**
     * Keep main thread alive until interrupted.
     * Override this method to implement custom termination logic.
     */
    protected void awaitTermination() throws InterruptedException {
        Thread.currentThread().join();
    }

    // ============================================================
    // Extension Hooks - Override these for custom behavior
    // ============================================================

    /**
     * Hook called before servers start.
     * Override to add custom initialization logic (e.g., load game data, connect to database).
     */
    protected void beforeServerStartup() throws Exception {
        // Default: do nothing
    }

    /**
     * Hook called after servers start.
     * Override to add custom post-startup logic (e.g., start background tasks).
     */
    protected void afterServerStartup() throws Exception {
        // Default: do nothing
    }

    /**
     * Hook called before servers stop.
     * Override to add custom cleanup logic (e.g., save game state).
     */
    protected void beforeServerShutdown() {
        // Default: do nothing
    }

    /**
     * Hook called after servers stop.
     * Override to add custom cleanup logic (e.g., close database connections).
     */
    protected void afterServerShutdown() {
        // Default: do nothing
    }

    /**
     * Check if the server is currently running.
     *
     * @return true if running, false otherwise
     */
    public final boolean isRunning() {
        return running;
    }

    /**
     * Get the server context.
     *
     * @return ServerContext instance, or null if not initialized
     */
    public final ServerContext getContext() {
        return context;
    }

    /**
     * Get the server manager.
     *
     * @return ServerManager instance, or null if not initialized
     */
    public final ServerManager getServerManager() {
        return serverManager;
    }
}
