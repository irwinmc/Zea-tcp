package com.akakata;

import com.akakata.banner.Banner;
import com.akakata.context.ServerContext;
import com.akakata.server.ServerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides the command-line server lifecycle and extension hooks.
 *
 * <p>{@link #run(String[])} starts the configured servers, registers a JVM shutdown
 * hook and waits for termination. Subclasses can customize startup and shutdown
 * through the protected hook methods.
 *
 * @author Kelvin
 */
public abstract class CommandLine {

    /**
     * Creates a command line instance.
     */
    public CommandLine() {
    }

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(CommandLine.class);

    /**
     * Application context owning configuration and server resources.
     */
    protected ServerContext context;
    /**
     * Manager coordinating startup and shutdown of the configured servers.
     */
    protected ServerManager serverManager;
    /**
     * Flag recording completion of server startup.
     */
    private volatile boolean running = false;

    /**
     * Starts the server lifecycle, registers a shutdown hook and waits for termination.
     *
     * <p>Startup failures are logged, trigger cleanup and exit the process with status 1.
     * The current implementation does not parse command-line arguments.
     *
     * @param args the command-line arguments, currently unused
     */
    public final void run(String[] args) {
        // Print banner
        printBanner();

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
     * Logs the configured banner and runtime identification.
     */
    protected void printBanner() {
        Banner.printBanner(LOG);
    }

    /**
     * Creates and initializes the context, runs startup hooks and starts enabled servers.
     *
     * @throws Exception if context creation, server startup or a startup hook fails
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
     * Runs shutdown hooks, stops servers and closes the context when marked running.
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
     * Creates the server context. Subclasses may select a different configuration resource.
     *
     * @return the new context
     * @throws Exception if configuration or context creation fails
     */
    protected ServerContext createServerContext() throws Exception {
        return new ServerContext();
    }

    /**
     * Registers a named JVM hook that invokes the shutdown sequence.
     */
    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutdown signal received");
            shutdown();
        }, "shutdown-hook"));
    }

    /**
     * Waits indefinitely by joining the current thread.
     *
     * @throws InterruptedException if the waiting thread is interrupted
     */
    protected void awaitTermination() throws InterruptedException {
        Thread.currentThread().join();
    }

    // ============================================================
    // Extension Hooks - Override these for custom behavior
    // ============================================================

    /**
     * Provides a hook after context initialization and before server startup. The default
     * implementation does nothing.
     *
     * @throws Exception if subclass initialization fails
     */
    protected void beforeServerStartup() throws Exception {
        // Default: do nothing
    }

    /**
     * Provides a hook after enabled servers start. The default implementation does nothing.
     *
     * @throws Exception if subclass post-startup work fails
     */
    protected void afterServerStartup() throws Exception {
        // Default: do nothing
    }

    /**
     * Provides a hook before servers stop. The default implementation does nothing.
     */
    protected void beforeServerShutdown() {
        // Default: do nothing
    }

    /**
     * Provides a hook after servers stop and before context closure. The default implementation
     * does nothing.
     */
    protected void afterServerShutdown() {
        // Default: do nothing
    }

    /**
     * Tests whether startup has marked this application as running.
     *
     * @return {@code true} if the application is marked running
     */
    public final boolean isRunning() {
        return running;
    }

    /**
     * Returns the context created during startup.
     *
     * @return the context, or {@code null} before it is created
     */
    public final ServerContext getContext() {
        return context;
    }

    /**
     * Returns the server manager resolved during startup.
     *
     * @return the manager, or {@code null} before it is resolved
     */
    public final ServerManager getServerManager() {
        return serverManager;
    }
}
