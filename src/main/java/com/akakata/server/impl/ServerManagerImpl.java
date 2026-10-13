package com.akakata.server.impl;

import com.akakata.context.AppContext;
import com.akakata.context.ConfigurationManager;
import com.akakata.lifecycle.ServerLifecycle;
import com.akakata.server.Server;
import com.akakata.server.ServerManager;
import com.akakata.server.ListenerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Controls enabled server instances using classpath configuration switches and ports.
 *
 * @author Kelvin
 */
public class ServerManagerImpl implements ServerManager {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ServerManagerImpl.class);

    /**
     * Configured TCP server instance.
     */
    private final Server tcpServer;

    /**
     * Configured HTTP server instance.
     */
    private final Server httpServer;

    /**
     * Configured WebSocket server instance.
     */
    private final Server webSocketServer;

    /**
     * Configuration manager supplying server ports, switches and sampling intervals.
     */
    private final ConfigurationManager configManager;

    /**
     * Server instances successfully started by this manager.
     */
    private final Set<Server> servers = new HashSet<>();

    /**
     * Lifecycle signal shared with application health checks.
     */
    private final ServerLifecycle lifecycle;

    /**
     * Listener state shared with health checks independently of this manager's lock.
     */
    private final ListenerRegistry listeners;

    /**
     * Creates a server manager impl instance with the supplied dependencies.
     *
     * @param tcpServer       the TCP server instance
     * @param httpServer      the HTTP server instance
     * @param webSocketServer the WebSocket server instance
     * @param configManager   the loaded configuration manager
     */
    public ServerManagerImpl(@Named(AppContext.TCP_SERVER) Server tcpServer,
                             @Named(AppContext.HTTP_SERVER) Server httpServer,
                             @Named(AppContext.WEB_SOCKET_SERVER) Server webSocketServer,
                             ConfigurationManager configManager) {
        this(tcpServer, httpServer, webSocketServer, configManager, new ServerLifecycle());
    }

    /**
     * Creates a manager publishing startup and shutdown through an injected lifecycle.
     *
     * @param tcpServer       TCP listener
     * @param httpServer      HTTP listener
     * @param webSocketServer WebSocket listener
     * @param configManager   loaded configuration
     * @param lifecycle       shared application lifecycle
     */
    public ServerManagerImpl(@Named(AppContext.TCP_SERVER) Server tcpServer,
                             @Named(AppContext.HTTP_SERVER) Server httpServer,
                             @Named(AppContext.WEB_SOCKET_SERVER) Server webSocketServer,
                             ConfigurationManager configManager, ServerLifecycle lifecycle) {
        this(tcpServer, httpServer, webSocketServer, configManager, lifecycle, new ListenerRegistry());
    }

    /**
     * Shares startup selection with the nonblocking listener readiness registry.
     *
     * @param tcpServer TCP listener
     * @param httpServer HTTP listener
     * @param webSocketServer WebSocket listener
     * @param configManager application configuration
     * @param lifecycle context lifecycle
     * @param listeners listener availability registry
     */
    @Inject
    public ServerManagerImpl(@Named(AppContext.TCP_SERVER) Server tcpServer,
                             @Named(AppContext.HTTP_SERVER) Server httpServer,
                             @Named(AppContext.WEB_SOCKET_SERVER) Server webSocketServer,
                             ConfigurationManager configManager, ServerLifecycle lifecycle, ListenerRegistry listeners) {
        this.lifecycle = java.util.Objects.requireNonNull(lifecycle);
        this.listeners = java.util.Objects.requireNonNull(listeners);
        this.tcpServer = tcpServer;
        this.httpServer = httpServer;
        this.webSocketServer = webSocketServer;
        this.configManager = configManager;
    }

    /**
     * Starts configured enabled servers, or positive-port servers when explicit ports are supplied.
     *
     * <p>The explicit-port overload ignores enable switches and the legacy Flash port.
     * Repeated calls are skipped while the started-server set is nonempty.
     *
     * @param flashPort     the legacy Flash port, unused by the default manager
     * @param tcpPort       the TCP override port; nonpositive values skip the server
     * @param httpPort      the HTTP override port; nonpositive values skip the server
     * @param webSocketPort the WebSocket override port; nonpositive values skip the server
     * @throws Exception if a selected server cannot start
     */
    @Override
    public synchronized void startServers(int flashPort, int tcpPort, int httpPort, int webSocketPort) throws Exception {
        if (!servers.isEmpty()) {
            LOG.warn("Servers already started, skipping duplicate start");
            return;
        }
        lifecycle.starting();
        try {
            publishListeners(tcpPort > 0, httpPort > 0, webSocketPort > 0);
            if (tcpPort > 0) {
                tcpServer.startServer(tcpPort);
                servers.add(tcpServer);
            }

            if (httpPort > 0) {
                httpServer.startServer(httpPort);
                servers.add(httpServer);
            }

            if (webSocketPort > 0) {
                webSocketServer.startServer(webSocketPort);
                servers.add(webSocketServer);
            }
            if (!servers.isEmpty()) {
                lifecycle.running();
            } else {
                lifecycle.initialized();
            }
        } catch (Exception failure) {
            lifecycle.failed();
            throw failure;
        }
    }

    /**
     * Starts configured enabled servers, or positive-port servers when explicit ports are supplied.
     *
     * <p>The explicit-port overload ignores enable switches and the legacy Flash port.
     * Repeated calls are skipped while the started-server set is nonempty.
     *
     * @throws Exception if a selected server cannot start
     */
    @Override
    public synchronized void startServers() throws Exception {
        if (!servers.isEmpty()) {
            LOG.warn("Servers already started, skipping duplicate start");
            return;
        }
        lifecycle.starting();
        try {
            boolean tcpEnabled = isServerEnabled("server.tcp.enabled");
            boolean httpEnabled = isServerEnabled("server.http.enabled");
            boolean webSocketEnabled = isServerEnabled("server.websocket.enabled");
            publishListeners(tcpEnabled, httpEnabled, webSocketEnabled);
            LOG.info("Starting servers based on configuration...");

            // Check TCP server
            if (tcpEnabled) {
                LOG.info("Starting TCP server on port {}...", configManager.getRequiredInt("tcp.port"));
                tcpServer.startServer();
                servers.add(tcpServer);
                LOG.info("TCP server started successfully");
            } else {
                LOG.info("TCP server disabled by configuration");
            }

            // Check HTTP server
            if (httpEnabled) {
                LOG.info("Starting HTTP server on port {}...", configManager.getRequiredInt("http.port"));
                httpServer.startServer();
                servers.add(httpServer);
                LOG.info("HTTP server started successfully");
            } else {
                LOG.info("HTTP server disabled by configuration");
            }

            // Check WebSocket server
            if (webSocketEnabled) {
                LOG.info("Starting WebSocket server on port {}...", configManager.getRequiredInt("web.socket.port"));
                webSocketServer.startServer();
                servers.add(webSocketServer);
                LOG.info("WebSocket server started successfully");
            } else {
                LOG.info("WebSocket server disabled by configuration");
            }

            if (servers.isEmpty()) {
                LOG.warn("No servers were started. All servers are disabled in configuration.");
            } else {
                LOG.info("Total servers started: {}", servers.size());
            }
            if (!servers.isEmpty()) {
                lifecycle.running();
            } else {
                lifecycle.initialized();
            }
        } catch (Exception failure) {
            lifecycle.failed();
            throw failure;
        }
    }

    /**
     * Reads a server-enable switch, treating absent switches as enabled.
     *
     * @param configKey the configuration key
     * @return {@code true} if the configured server is enabled
     */
    private boolean isServerEnabled(String configKey) {
        return configManager.getBoolean(configKey, true);
    }

    /**
     * Publishes the entire expected set before any selected server begins binding.
     *
     * @param tcp whether TCP is selected
     * @param http whether HTTP is selected
     * @param webSocket whether WebSocket is selected
     */
    private void publishListeners(boolean tcp, boolean http, boolean webSocket) {
        Map<String, Server> selected = new LinkedHashMap<>();
        if (tcp) {
            selected.put("tcp", tcpServer);
        }
        if (http) {
            selected.put("http", httpServer);
        }
        if (webSocket) {
            selected.put("websocket", webSocketServer);
        }
        listeners.expect(selected);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stopServers() throws Exception {
        lifecycle.stopping();
        Set<Server> snapshot;
        synchronized (this) {
            snapshot = Set.copyOf(servers);
            servers.clear();
        }
        Exception failure = null;
        for (Server server : snapshot) {
            try {
                server.stopServer();
            } catch (Exception problem) {
                LOG.error("Unable to stop server {}", server, problem);
                if (failure == null) {
                    failure = problem;
                } else {
                    failure.addSuppressed(problem);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
