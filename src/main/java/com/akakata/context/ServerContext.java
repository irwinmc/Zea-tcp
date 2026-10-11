package com.akakata.context;

import com.akakata.app.Game;
import com.akakata.context.module.DaggerGameServerComponent;
import com.akakata.context.module.GameServerComponent;
import com.akakata.event.impl.EventDispatchers;
import com.akakata.metrics.EventDispatcherMetrics;
import com.akakata.server.ServerManager;
import com.akakata.service.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Owns configuration, network resources and the Dagger application component.
 *
 * <p>Initialization is idempotent until closure and cleans up partial construction
 * on failure. Shutdown stops servers, closes the game and session service, stops
 * metrics, releases the shared dispatcher and terminates event loops. Failures in
 * one cleanup stage are logged so later stages can still execute.
 *
 * @author Kelvin
 */
public final class ServerContext implements AutoCloseable {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ServerContext.class);

    /**
     * Default classpath properties resource.
     */
    private static final String DEFAULT_CONFIG_PATH = "props/conf.properties";

    /**
     * Configuration manager supplying server ports, switches and sampling intervals.
     */
    private final ConfigurationManager configManager;

    /**
     * Owner of the shared boss and worker event-loop groups.
     */
    private final NetworkBootstrap networkBootstrap;

    /**
     * Singleton sampler for shared-dispatcher queue depths.
     */
    private EventDispatcherMetrics dispatcherMetrics;

    /**
     * Flag recording successful application initialization.
     */
    private volatile boolean initialized = false;

    /**
     * Terminal lifecycle flag, published when shutdown begins.
     */
    private boolean closed = false;

    /**
     * Game associated with this session or component.
     */
    private volatile Game game;

    /**
     * Credential-keyed session registry and session resource owner.
     */
    private volatile SessionService sessionService;

    /**
     * Manager coordinating startup and shutdown of the configured servers.
     */
    private volatile ServerManager serverManager;

    /**
     * Loads configuration and creates network groups. Application resources are created by
     * initialize().
     *
     * @throws IOException if the classpath configuration cannot be loaded
     */
    public ServerContext() throws IOException {
        this(DEFAULT_CONFIG_PATH);
    }

    /**
     * Loads configuration and creates network groups. Application resources are created by
     * initialize().
     *
     * @param configPath the classpath properties resource name
     * @throws IOException if the classpath configuration cannot be loaded
     */
    public ServerContext(String configPath) throws IOException {
        this.configManager = new ConfigurationManager(configPath);

        int bossThreads = configManager.getInt("bossThreadCount", 1);
        int workerThreads = configManager.getInt("workerThreadCount",
                Runtime.getRuntime().availableProcessors());

        this.networkBootstrap = new NetworkBootstrap(bossThreads, workerThreads);

        LOG.info("ServerContext created");
    }

    /**
     * Builds and retains the application component resources once.
     *
     * <p>If initialization fails, closes resources already constructed and rethrows
     * the failure. An already initialized open context is left unchanged.
     *
     * @throws IllegalStateException if this context has been closed
     */
    public synchronized void initialize() {
        if (closed) {
            throw new IllegalStateException("ServerContext is closed");
        }
        if (initialized) {
            LOG.warn("ServerContext already initialized");
            return;
        }

        LOG.info("Initializing ServerContext...");
        try {
            GameServerComponent component = DaggerGameServerComponent.builder()
                    .configurationManager(configManager)
                    .networkBootstrap(networkBootstrap)
                    .build();

            // Retain ownership before building servers so partial initialization can be cleaned up.
            game = component.game();
            sessionService = component.sessionService();

            // Initialize ServerManager (it manages all servers internally)
            serverManager = component.serverManager();

            startDispatcherMetrics();
            initialized = true;
            LOG.info("ServerContext initialization complete");
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    /**
     * Resolves one of the exposed application resources by type.
     *
     * <p>Supported types are Game, SessionService and ServerManager. Before successful
     * initialization, the corresponding field may be null.
     *
     * @param <T> the requested value type
     * @param type the event opcode
     * @return the resource cast to the requested type, or {@code null} before it is initialized
     * @throws IllegalArgumentException if the requested type is not exposed
     */
    public <T> T getBean(Class<T> type) {
        if (type == ServerManager.class) {
            return type.cast(serverManager);
        }
        if (type == Game.class) {
            return type.cast(game);
        }
        if (type == SessionService.class) {
            return type.cast(sessionService);
        }
        throw new IllegalArgumentException("No bean found for type: " + type.getName());
    }

    /**
     * Returns the configuration loaded by this context.
     *
     * @return the configuration manager
     */
    public ConfigurationManager getConfigManager() {
        return configManager;
    }

    /**
     * Closes application and network resources in dependency order.
     *
     * <p>Repeated calls have no effect. Exceptions from individual cleanup stages are
     * logged and do not prevent subsequent stages from running.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        LOG.info("Shutting down ServerContext");

        // Stop all servers via ServerManager
        if (serverManager != null) {
            try {
                serverManager.stopServers();
            } catch (Exception e) {
                LOG.error("Error stopping servers", e);
            }
        }

        // Close application resources while dispatchers and network event loops are available.
        if (game != null) {
            try {
                game.close();
            } catch (Exception e) {
                LOG.error("Error closing game", e);
            }
        }
        if (sessionService != null) {
            try {
                sessionService.close();
            } catch (Exception e) {
                LOG.error("Error closing session service", e);
            }
        }

        // Stop producers before shutting down the shared dispatcher.
        if (dispatcherMetrics != null) {
            try {
                dispatcherMetrics.stop();
            } catch (Exception e) {
                LOG.error("Error stopping dispatcher metrics", e);
            }
        }

        // Avoid creating the shared dispatcher when closing an uninitialized context.
        if (game != null || sessionService != null) {
            try {
                EventDispatchers.shutdownSharedDispatcher();
            } catch (Exception e) {
                LOG.error("Error closing shared event dispatcher", e);
            }
        }

        try {
            networkBootstrap.close();
        } catch (Exception e) {
            LOG.error("Error closing network resources", e);
        }

        LOG.info("ServerContext shutdown complete");
    }

    /**
     * Starts shared-dispatcher metric sampling using the configured interval in seconds.
     */
    private void startDispatcherMetrics() {
        long intervalSeconds = configManager.getInt("metrics.event.interval.seconds", 30);
        dispatcherMetrics = EventDispatcherMetrics.getInstance();
        dispatcherMetrics.start(intervalSeconds);
    }
}
