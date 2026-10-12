package com.akakata.context;

import com.akakata.app.Game;
import com.akakata.communication.OutboundBudget;
import com.akakata.context.module.DaggerGameServerComponent;
import com.akakata.context.module.GameServerComponent;
import com.akakata.context.module.ServiceModule;
import com.akakata.event.impl.EventDispatchers;
import com.akakata.event.impl.NettyEventDispatcher;
import com.akakata.lifecycle.AsyncCloseable;
import com.akakata.lifecycle.ServerLifecycle;
import com.akakata.lifecycle.ShutdownRegistry;
import com.akakata.lifecycle.ShutdownSupport;
import com.akakata.lifecycle.ShutdownTimeoutException;
import com.akakata.metrics.EventDispatcherMetrics;
import com.akakata.metrics.FlowControlMetrics;
import com.akakata.metrics.ServerHealth;
import com.akakata.security.CredentialsVerifier;
import com.akakata.server.ServerManager;
import com.akakata.service.LoginService;
import com.akakata.service.SecurityService;
import com.akakata.service.SessionService;
import com.akakata.service.task.BlockingTaskExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Owns configuration, network resources and the Dagger application component.
 *
 * <p>Initialization is idempotent until closure and cleans up partial construction
 * on failure. Application providers can replace core implementations while keeping
 * the classpath properties loader. Shutdown stops servers and blocking result
 * producers, closes the game and session service, stops
 * metrics, releases the shared dispatcher and terminates event loops. Failures in
 * one cleanup stage are logged so later stages can still execute.
 *
 * @author Kelvin
 */
public final class ServerContext implements AsyncCloseable {

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
     * Immutable provider choices supplied by the application.
     */
    private final ServerComponents components;

    /**
     * Application state published independently of HTTP handlers.
     */
    private final ServerLifecycle lifecycle = new ServerLifecycle();

    /**
     * Shared external shutdown waiting budget in milliseconds.
     */
    private final long shutdownMillis;

    /**
     * Resource ownership shared by initialization and asynchronous cleanup.
     */
    private final ShutdownRegistry ownership;

    /**
     * Serializes initialization without delaying shutdown requests.
     */
    private final Object initializationLock = new Object();

    /**
     * Assembly thread, published under the context monitor.
     */
    private volatile Thread initializingThread;

    /**
     * Actual completion of all initiated cleanup stages.
     */
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();

    /**
     * Selected authentication implementation, exposed without lazy creation after shutdown.
     */
    private volatile CredentialsVerifier verifier;

    /**
     * Selected token implementation.
     */
    private volatile SecurityService security;

    /**
     * Selected login coordinator.
     */
    private volatile LoginService login;

    /**
     * Context-wide outbound reservation owner.
     */
    private volatile OutboundBudget outboundBudget;

    /**
     * Context-owned bounded workers for detached slow business operations.
     */
    private volatile BlockingTaskExecutor blockingTasks;

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
    private volatile boolean closed = false;

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
     * Nonblocking health provider retained after assembly.
     */
    private volatile ServerHealth health;

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
        this(configPath, ServerComponents.builder().build());
    }

    /**
     * Loads classpath properties and selects application core providers.
     *
     * <p>Factories run during initialization. The context closes the returned Game,
     * SessionService and its blocking workers, including partially initialized resources.
     *
     * @param configPath the classpath properties resource name
     * @param components the application's immutable provider choices
     * @throws IOException if the configuration resource cannot be loaded
     */
    public ServerContext(String configPath, ServerComponents components) throws IOException {
        this.components = java.util.Objects.requireNonNull(components);
        this.configManager = new ConfigurationManager(configPath);

        int bossThreads = configManager.getInt("bossThreadCount", 1);
        int workerThreads = configManager.getInt("workerThreadCount",
                Runtime.getRuntime().availableProcessors());

        this.shutdownMillis = configManager.getLong("server.shutdown.timeout.millis", ShutdownSupport.DEFAULT_TIMEOUT_MILLIS);
        if (shutdownMillis <= 0) {
            throw new IllegalArgumentException("server.shutdown.timeout.millis must be positive");
        }
        this.ownership = new ShutdownRegistry(shutdownMillis);
        this.networkBootstrap = ownership.own(new NetworkBootstrap(bossThreads, workerThreads, shutdownMillis),
                "network", 70, resource -> resource.closeAsync().join());

        LOG.info("ServerContext created");
    }

    /**
     * Builds and retains the application component resources once.
     *
     * <p>If initialization fails, closes resources already constructed and rethrows
     * the failure. An already initialized open context is left unchanged. Shutdown
     * does not wait for application factories; resources returned after a close
     * request are accepted and immediately closed before completion is reported.
     *
     * @throws IllegalStateException if this context has been closed
     */
    public void initialize() {
        boolean assembling = false;
        try {
            requireOpen();
            synchronized (initializationLock) {
                synchronized (this) {
                    requireOpen();
                    if (initialized) {
                        LOG.warn("ServerContext already initialized");
                        return;
                    }
                    initializingThread = Thread.currentThread();
                    assembling = true;
                }
                try {
                    assembleComponents();
                    synchronized (this) {
                        requireOpen();
                        initialized = true;
                        lifecycle.initialized();
                    }
                    LOG.info("ServerContext initialization complete");
                } finally {
                    synchronized (this) {
                        initializingThread = null;
                    }
                    ownership.seal();
                }
            }
        } catch (RuntimeException | Error failure) {
            if (assembling && !closed) {
                lifecycle.failed();
                try {
                    close();
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    /**
     * Builds the composition and accepts each returned resource before continuing.
     *
     * @throws IllegalStateException if closure is requested during assembly
     */
    private void assembleComponents() {
        LOG.info("Initializing ServerContext...");
        EventDispatchers.configure(configManager.getInt("session.dispatcher.shards",
                        Math.max(2, Integer.highestOneBit(Runtime.getRuntime().availableProcessors()))),
                configManager.getInt("session.queue.capacity", NettyEventDispatcher.DEFAULT_QUEUE_CAPACITY),
                configManager.getLong("session.queue.bytes", NettyEventDispatcher.DEFAULT_QUEUE_BYTES));
        ownership.own(EventDispatchers.sharedGroup(), "shared-events", 60,
                resource -> EventDispatchers.shutdownSharedDispatcher());
        requireOpen();
        GameServerComponent component = DaggerGameServerComponent.builder()
                .configurationManager(configManager)
                .networkBootstrap(networkBootstrap)
                .serviceModule(new ServiceModule(components, lifecycle))
                .build();

        verifier = component.credentialsVerifier();
        security = component.securityService();
        outboundBudget = component.outboundBudget();

        // Retain ownership before building servers so partial initialization can be cleaned up.
        game = ownership.own(component.game(), "game", 30, Game::close);
        requireOpen();
        sessionService = ownership.own(component.sessionService(), "sessions", 40, SessionService::close);
        requireOpen();
        blockingTasks = ownership.own(component.blockingTaskExecutor(), "blocking", 20, BlockingTaskExecutor::close);
        requireOpen();
        login = component.loginService();

        serverManager = ownership.own(component.serverManager(), "servers", 10, resource -> {
            try {
                resource.stopServers();
            } catch (Exception failure) {
                throw new IllegalStateException("Server shutdown failed", failure);
            }
        });
        requireOpen();
        health = component.serverHealth();

        try {
            FlowControlMetrics.gameDispatcher(game.getEventDispatcher() instanceof NettyEventDispatcher dispatcher ? dispatcher : null);
            FlowControlMetrics.resources(outboundBudget, blockingTasks);
            startDispatcherMetrics();
        } finally {
            ownership.own(this, "metrics", 50, ServerContext::stopMetrics);
        }
    }

    /**
     * Rejects assembly work after the terminal close request.
     */
    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("ServerContext is closed");
        }
    }

    /**
     * Resolves one of the exposed application resources by type.
     *
     * <p>Supported types include Game, SessionService, ServerManager, CredentialsVerifier,
     * SecurityService, LoginService, OutboundBudget, BlockingTaskExecutor, NetworkBootstrap,
     * ServerLifecycle and ServerHealth. Before successful
     * initialization, the corresponding field may be null.
     *
     * @param <T>  the requested value type
     * @param type the supported resource type
     * @return the resource cast to the requested type, or {@code null} before it is initialized
     * @throws IllegalArgumentException if the requested type is not exposed
     */
    public <T> T getBean(Class<T> type) {
        if (type == NetworkBootstrap.class) {
            return type.cast(networkBootstrap);
        }
        if (type == ServerHealth.class) {
            return type.cast(health);
        }
        if (type == ServerLifecycle.class) {
            return type.cast(lifecycle);
        }
        if (type == ServerManager.class) {
            return type.cast(serverManager);
        }
        if (type == Game.class) {
            return type.cast(game);
        }
        if (type == SessionService.class) {
            return type.cast(sessionService);
        }
        if (type == BlockingTaskExecutor.class) {
            return type.cast(blockingTasks);
        }
        if (type == CredentialsVerifier.class) {
            return type.cast(verifier);
        }
        if (type == SecurityService.class) {
            return type.cast(security);
        }
        if (type == LoginService.class) {
            return type.cast(login);
        }
        if (type == OutboundBudget.class) {
            return type.cast(outboundBudget);
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
     * Requests cleanup and waits outside managed workers within the configured budget.
     *
     * <p>Cleanup failures are logged for compatibility. A waiting timeout is explicit;
     * cleanup continues and its actual outcome remains available through closeAsync().
     *
     * @throws ShutdownTimeoutException if cleanup exceeds the waiting interval
     */
    @Override
    public void close() {
        CompletableFuture<Void> completion = closeAsync();
        if (ShutdownSupport.isCleanupAction() || Thread.currentThread() == initializingThread) {
            return;
        }
        try {
            ShutdownSupport.await(completion, shutdownMillis, "server context");
        } catch (ShutdownTimeoutException timeout) {
            LOG.warn("ServerContext cleanup continues after the caller's deadline", timeout);
            throw timeout;
        } catch (IllegalStateException failure) {
            LOG.error("ServerContext cleanup completed with failures", failure);
        }
    }

    /**
     * Withdraws readiness and initiates cleanup without holding the context monitor.
     *
     * <p>Every stage is initiated even when an earlier application cleanup stalls.
     * Managed callbacks can request closure safely. The returned future observes
     * actual actions and cannot cancel internal cleanup ownership.
     *
     * @return an independent observation of all cleanup actions
     */
    @Override
    public CompletableFuture<Void> closeAsync() {
        synchronized (this) {
            if (closed) {
                return closeCompletion.copy();
            }
            closed = true;
            lifecycle.stopping();
            if (initializingThread == null) {
                ownership.seal();
            }
        }
        LOG.info("Shutting down ServerContext");
        ownership.closeAsync().whenComplete((ignored, failure) -> {
            if (failure == null) {
                lifecycle.terminated();
                LOG.info("ServerContext shutdown complete");
                ShutdownSupport.complete(closeCompletion, null);
            } else {
                lifecycle.failed();
                ShutdownSupport.complete(closeCompletion, failure);
            }
        });
        return closeCompletion.copy();
    }

    /**
     * Exposes lifecycle state without exposing resource cleanup internals.
     *
     * @return application lifecycle signal
     */
    public ServerLifecycle getLifecycle() {
        return lifecycle;
    }

    /**
     * Starts shared-dispatcher metric sampling using the configured interval in seconds.
     */
    private void startDispatcherMetrics() {
        long intervalSeconds = configManager.getInt("metrics.event.interval.seconds", 30);
        dispatcherMetrics = EventDispatcherMetrics.getInstance();
        dispatcherMetrics.start(intervalSeconds);
    }

    /**
     * Stops context-owned sampling and clears references held by shared metrics.
     */
    private void stopMetrics() {
        try {
            if (dispatcherMetrics != null) {
                dispatcherMetrics.stop();
            }
        } finally {
            FlowControlMetrics.gameDispatcher(null);
            FlowControlMetrics.resources(null, null);
        }
    }
}
