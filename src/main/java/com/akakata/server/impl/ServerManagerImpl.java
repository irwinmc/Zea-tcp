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
 * Implementation of ServerManager that manages multiple server instances.
 * Supports selective server startup based on configuration.
 *
 * @author Kelvin
 */
public class ServerManagerImpl implements ServerManager {

    private static final Logger LOG = LoggerFactory.getLogger(ServerManagerImpl.class);

    private final Server tcpServer;
    private final Server httpServer;
    private final Server webSocketServer;
    private final ConfigurationManager configManager;
    private final Set<Server> servers = new HashSet<>();

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

    @Override
    public void startServers() throws Exception {
        if (!servers.isEmpty()) {
            LOG.warn("Servers already started, skipping duplicate start");
            return;
        }

        LOG.info("Starting servers based on configuration...");

        // Check TCP server
        if (isServerEnabled("server.tcp.enabled")) {
            LOG.info("Starting TCP server on port {}...", configManager.getInt("tcp.port", 8090));
            tcpServer.startServer();
            servers.add(tcpServer);
            LOG.info("TCP server started successfully");
        } else {
            LOG.info("TCP server disabled by configuration");
        }

        // Check HTTP server
        if (isServerEnabled("server.http.enabled")) {
            LOG.info("Starting HTTP server on port {}...", configManager.getInt("http.port", 8081));
            httpServer.startServer();
            servers.add(httpServer);
            LOG.info("HTTP server started successfully");
        } else {
            LOG.info("HTTP server disabled by configuration");
        }

        // Check WebSocket server
        if (isServerEnabled("server.websocket.enabled")) {
            LOG.info("Starting WebSocket server on port {}...", configManager.getInt("web.socket.port", 8300));
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
     * Check if a server is enabled in configuration.
     *
     * @param configKey the configuration key
     * @return true if enabled (default), false otherwise
     */
    private boolean isServerEnabled(String configKey) {
        return configManager.getBoolean(configKey, true);
    }

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
