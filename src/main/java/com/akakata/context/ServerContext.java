package com.akakata.context;

import com.akakata.context.module.DaggerGameServerComponent;
import com.akakata.context.module.GameServerComponent;
import com.akakata.event.impl.EventDispatcherMetrics;
import com.akakata.event.impl.EventDispatchers;
import com.akakata.server.Server;
import com.akakata.service.TaskManagerService;
import com.akakata.service.impl.SimpleTaskManagerServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private GameServerComponent component;
    private EventDispatcherMetrics dispatcherMetrics;
    private TaskManagerService taskManagerService;
    private volatile boolean initialized = false;
    private final Map<String, Server> servers = new ConcurrentHashMap<>();

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
        component = DaggerGameServerComponent.builder()
                .configurationManager(configManager)
                .networkBootstrap(networkBootstrap)
                .build();

        servers.put(AppContext.TCP_SERVER, component.tcpServer());
        servers.put(AppContext.HTTP_SERVER, component.httpServer());
        servers.put(AppContext.WEB_SOCKET_SERVER, component.webSocketServer());
        taskManagerService = component.taskManagerService();

        startDispatcherMetrics();
        initialized = true;
        LOG.info("ServerContext initialization complete");
    }

    /**
     * Get bean by name and type.
     */
    public <T> T getBean(String name, Class<T> type) {
        Object bean = getBean(name);
        if (bean == null) {
            return null;
        }
        if (!type.isInstance(bean)) {
            throw new IllegalArgumentException("Bean " + name + " is not of type " + type.getName());
        }
        return type.cast(bean);
    }

    /**
     * Get bean by name.
     */
    public Object getBean(String name) {
        return servers.get(name);
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

        shutdownTaskManager();

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

    private void shutdownTaskManager() {
        if (taskManagerService instanceof SimpleTaskManagerServiceImpl simpleExecutor) {
            simpleExecutor.shutdown();
        }
    }

    private void stopServer(String beanName, String serverType) {
        Server server = servers.get(beanName);
        if (server == null) {
            return;
        }
        try {
            server.stopServer();
            LOG.info("{} server stopped", serverType);
        } catch (Exception e) {
            LOG.error("Error stopping {} server", serverType, e);
        }
    }
}
