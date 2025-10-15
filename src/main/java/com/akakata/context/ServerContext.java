package com.akakata.context;

import com.akakata.event.impl.EventDispatcherMetrics;
import com.akakata.event.impl.EventDispatchers;
import com.akakata.server.Server;
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
    private final BeanRegistry beanRegistry;
    private final BeanFactory beanFactory;
    private EventDispatcherMetrics dispatcherMetrics;
    private volatile boolean initialized = false;

    public ServerContext() throws IOException {
        this(DEFAULT_CONFIG_PATH);
    }

    public ServerContext(String configPath) throws IOException {
        // Create specialized components
        this.configManager = new ConfigurationManager(configPath);

        int bossThreads = configManager.getInt("bossThreadCount", 1);
        int workerThreads = configManager.getInt("workerThreadCount",
                Runtime.getRuntime().availableProcessors());

        this.networkBootstrap = new NetworkBootstrap(bossThreads, workerThreads);
        this.beanRegistry = new BeanRegistry();
        this.beanFactory = new BeanFactory(configManager, networkBootstrap, beanRegistry);

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
        beanFactory.createBeans();
        startDispatcherMetrics();
        initialized = true;
        LOG.info("ServerContext initialization complete with {} beans", beanRegistry.size());
    }

    /**
     * Get bean by name and type.
     */
    public <T> T getBean(String name, Class<T> type) {
        return beanRegistry.getBean(name, type);
    }

    /**
     * Get bean by name.
     */
    public Object getBean(String name) {
        return beanRegistry.getBean(name);
    }

    /**
     * Get configuration manager for direct access.
     */
    public ConfigurationManager getConfigManager() {
        return configManager;
    }

    @Override
    public void close() {
        LOG.info("Shutting down ServerContext");

        // Stop servers
        stopServers();

        // Close network resources
        networkBootstrap.close();

        // Shutdown shared event dispatcher
        EventDispatchers.shutdownSharedDispatcher();

        if (dispatcherMetrics != null) {
            dispatcherMetrics.stop();
        }

        // Clear beans
        beanRegistry.clear();

        LOG.info("ServerContext shutdown complete");
    }

    private void stopServers() {
        stopServer(AppContext.TCP_SERVER, "TCP");
        stopServer(AppContext.HTTP_SERVER, "HTTP");
        stopServer(AppContext.WEB_SOCKET_SERVER, "WebSocket");
    }

    private void startDispatcherMetrics() {
        long intervalSeconds = configManager.getInt("metrics.event.interval.seconds", 30);
        dispatcherMetrics = new EventDispatcherMetrics();
        dispatcherMetrics.start(intervalSeconds);
    }

    private void stopServer(String beanName, String serverType) {
        try {
            Server server = (Server) beanRegistry.getBean(beanName);
            if (server != null) {
                server.stopServer();
                LOG.info("{} server stopped", serverType);
            }
        } catch (Exception e) {
            LOG.error("Error stopping {} server", serverType, e);
        }
    }
}
