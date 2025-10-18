package com.akakata.context;

import com.akakata.context.module.DaggerGameServerComponent;
import com.akakata.context.module.GameServerComponent;
import com.akakata.event.impl.EventDispatcherMetrics;
import com.akakata.event.impl.EventDispatchers;
import com.akakata.server.ServerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Lightweight application context coordinator.
 * Delegates to specialized components instead of doing everything itself.
 *
 * @author Kelvin
 */
public final class ServerContext implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ServerContext.class);
    private static final String DEFAULT_CONFIG_PATH = "props/conf.properties";

    private final ConfigurationManager configManager;
    private final NetworkBootstrap networkBootstrap;
    private EventDispatcherMetrics dispatcherMetrics;
    private volatile boolean initialized = false;
    private volatile ServerManager serverManager;

    public ServerContext() throws IOException {
        this(DEFAULT_CONFIG_PATH);
    }

    public ServerContext(String configPath) throws IOException {
        this.configManager = new ConfigurationManager(configPath);

        int bossThreads = configManager.getInt("bossThreadCount", 1);
        int workerThreads = configManager.getInt("workerThreadCount",
                Runtime.getRuntime().availableProcessors());

        this.networkBootstrap = new NetworkBootstrap(bossThreads, workerThreads);

        LOG.info("ServerContext created");
    }

    /**
     * Initialize all beans.
     */
    public void initialize() {
        if (initialized) {
            LOG.warn("ServerContext already initialized");
            return;
        }

        LOG.info("Initializing ServerContext...");
        GameServerComponent component = DaggerGameServerComponent.builder()
                .configurationManager(configManager)
                .networkBootstrap(networkBootstrap)
                .build();

        // Initialize ServerManager (it manages all servers internally)
        serverManager = component.serverManager();

        startDispatcherMetrics();
        initialized = true;
        LOG.info("ServerContext initialization complete");
    }

    /**
     * Get bean by type.
     * Currently only supports ServerManager.
     */
    public <T> T getBean(Class<T> type) {
        if (type == ServerManager.class) {
            return type.cast(serverManager);
        }
        throw new IllegalArgumentException("No bean found for type: " + type.getName());
    }

    /**
     * Get the configuration manager.
     *
     * @return ConfigurationManager instance
     */
    public ConfigurationManager getConfigManager() {
        return configManager;
    }

    @Override
    public void close() {
        LOG.info("Shutting down ServerContext");

        // Stop all servers via ServerManager
        if (serverManager != null) {
            try {
                serverManager.stopServers();
            } catch (Exception e) {
                LOG.error("Error stopping servers", e);
            }
        }

        // Close network resources
        networkBootstrap.close();

        // Shutdown shared event dispatcher
        EventDispatchers.shutdownSharedDispatcher();

        if (dispatcherMetrics != null) {
            dispatcherMetrics.stop();
        }

        LOG.info("ServerContext shutdown complete");
    }

    private void startDispatcherMetrics() {
        long intervalSeconds = configManager.getInt("metrics.event.interval.seconds", 30);
        dispatcherMetrics = new EventDispatcherMetrics();
        dispatcherMetrics.start(intervalSeconds);
    }
}
