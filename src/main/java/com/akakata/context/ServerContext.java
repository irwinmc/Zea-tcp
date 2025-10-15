package com.akakata.context;

import com.akakata.context.module.GameServerModule;
import com.akakata.event.impl.EventDispatcherMetrics;
import com.akakata.event.impl.EventDispatchers;
import com.akakata.server.Server;
import com.akakata.service.TaskManagerService;
import com.akakata.service.impl.SimpleTaskManagerServiceImpl;
import com.google.inject.ConfigurationException;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provider;
import com.google.inject.name.Names;
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
    private Injector injector;
    private EventDispatcherMetrics dispatcherMetrics;
    private volatile boolean initialized = false;
    private final Map<String, Provider<?>> namedProviders = new ConcurrentHashMap<>();

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
        injector = com.google.inject.Guice.createInjector(new GameServerModule(configManager, networkBootstrap));
        registerNamedProviders();
        startDispatcherMetrics();
        initialized = true;
        LOG.info("ServerContext initialization complete");
    }

    /**
     * Get bean by name and type.
     */
    public <T> T getBean(String name, Class<T> type) {
        Provider<?> provider = namedProviders.get(name);
        if (provider == null && injector != null) {
            try {
                Provider<T> typedProvider = injector.getProvider(Key.get(type, Names.named(name)));
                namedProviders.put(name, typedProvider);
                provider = typedProvider;
            } catch (ConfigurationException ex) {
                LOG.warn("No binding found for bean {} of type {}", name, type.getSimpleName());
                return null;
            }
        }
        if (provider == null) {
            return null;
        }
        return type.cast(provider.get());
    }

    /**
     * Get bean by name.
     */
    public Object getBean(String name) {
        Provider<?> provider = namedProviders.get(name);
        return provider != null ? provider.get() : null;
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

    private void registerNamedProviders() {
        if (injector == null) {
            return;
        }
        registerProvider(AppContext.TCP_SERVER, Key.get(Server.class, Names.named(AppContext.TCP_SERVER)));
        registerProvider(AppContext.HTTP_SERVER, Key.get(Server.class, Names.named(AppContext.HTTP_SERVER)));
        registerProvider(AppContext.WEB_SOCKET_SERVER, Key.get(Server.class, Names.named(AppContext.WEB_SOCKET_SERVER)));
    }

    private void registerProvider(String name, Key<?> key) {
        try {
            namedProviders.put(name, injector.getProvider(key));
        } catch (ConfigurationException ex) {
            LOG.debug("Optional binding {} not available", name);
        }
    }

    private void shutdownTaskManager() {
        if (injector == null) {
            return;
        }
        try {
            TaskManagerService taskManagerService = injector.getInstance(TaskManagerService.class);
            if (taskManagerService instanceof SimpleTaskManagerServiceImpl simpleExecutor) {
                simpleExecutor.shutdown();
            }
        } catch (ConfigurationException ex) {
            LOG.debug("TaskManagerService not bound, skip shutdown");
        }
    }

    private void stopServer(String beanName, String serverType) {
        try {
            Server server = getBean(beanName, Server.class);
            if (server != null) {
                server.stopServer();
                LOG.info("{} server stopped", serverType);
            }
        } catch (Exception e) {
            LOG.error("Error stopping {} server", serverType, e);
        }
    }
}
