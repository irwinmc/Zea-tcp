package com.akakata.server.impl;

import com.akakata.context.AppContext;
import com.akakata.server.Server;
import com.akakata.server.ServerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.HashSet;
import java.util.Set;

/**
 * @author Kelvin
 */
public class ServerManagerImpl implements ServerManager {

    private static final Logger LOG = LoggerFactory.getLogger(ServerManagerImpl.class);

    private final Server tcpServer;
    private final Server httpServer;
    private final Server webSocketServer;
    private final Set<Server> servers = new HashSet<>();

    @Inject
    public ServerManagerImpl(@Named(AppContext.TCP_SERVER) Server tcpServer,
                             @Named(AppContext.HTTP_SERVER) Server httpServer,
                             @Named(AppContext.WEB_SOCKET_SERVER) Server webSocketServer) {
        this.tcpServer = tcpServer;
        this.httpServer = httpServer;
        this.webSocketServer = webSocketServer;
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

        tcpServer.startServer();
        servers.add(tcpServer);

        httpServer.startServer();
        servers.add(httpServer);

        webSocketServer.startServer();
        servers.add(webSocketServer);
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
