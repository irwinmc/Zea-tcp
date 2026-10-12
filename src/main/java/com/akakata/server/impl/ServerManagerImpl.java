package com.akakata.server.impl;

import com.akakata.context.AppContext;
import com.akakata.context.ConfigurationManager;
import com.akakata.server.Server;
import com.akakata.server.ServerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.HashSet;
import java.util.Set;

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
     * Creates a server manager impl instance with the supplied dependencies.
     *
     * @param tcpServer       the TCP server instance
     * @param httpServer      the HTTP server instance
     * @param webSocketServer the WebSocket server instance
     * @param configManager   the loaded configuration manager
     */
    @Inject
    public ServerManagerImpl(@Named(AppContext.TCP_SERVER) Server tcpServer,
                             @Named(AppContext.HTTP_SERVER) Server httpServer,
                             @Named(AppContext.WEB_SOCKET_SERVER) Server webSocketServer,
                             ConfigurationManager configManager) {
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
    public void startServers(int flashPort, int tcpPort, int httpPort, int webSocketPort) throws Exception {
        if (!servers.isEmpty()) {
            LOG.warn("Servers already started, skipping duplicate start");
            return;
        }

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
    public void startServers() throws Exception {
        if (!servers.isEmpty()) {
            LOG.warn("Servers already started, skipping duplicate start");
            return;
        }

        LOG.info("Starting servers based on configuration...");

        // Check TCP server
        if (isServerEnabled("server.tcp.enabled")) {
            LOG.info("Starting TCP server on port {}...", configManager.getRequiredInt("tcp.port"));
            tcpServer.startServer();
            servers.add(tcpServer);
            LOG.info("TCP server started successfully");
        } else {
            LOG.info("TCP server disabled by configuration");
        }

        // Check HTTP server
        if (isServerEnabled("server.http.enabled")) {
            LOG.info("Starting HTTP server on port {}...", configManager.getRequiredInt("http.port"));
            httpServer.startServer();
            servers.add(httpServer);
            LOG.info("HTTP server started successfully");
        } else {
            LOG.info("HTTP server disabled by configuration");
        }

        // Check WebSocket server
        if (isServerEnabled("server.websocket.enabled")) {
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
     * {@inheritDoc}
     */
    @Override
    public void stopServers() throws Exception {
        for (Server nettyServer : servers) {
            try {
                nettyServer.stopServer();
            } catch (Exception e) {
                LOG.error("Unable to stop server {} due to error {}", nettyServer, e);
                throw e;
            }
        }
        servers.clear();
    }
}
