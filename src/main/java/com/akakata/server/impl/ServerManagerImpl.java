package com.akakata.server.impl;

import com.akakata.context.AppContext;
import com.akakata.server.ServerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * @author Kelvin
 */
public class ServerManagerImpl implements ServerManager {

    private static final Logger LOG = LoggerFactory.getLogger(ServerManagerImpl.class);

    protected Set<AbstractNettyServer> servers;

    public ServerManagerImpl() {
        servers = new HashSet<>();
    }

    @Override
    public void startServers(int flashPort, int tcpPort, int httpPort, int webSocketPort) throws Exception {
        if (flashPort > 0) {
            AbstractNettyServer flashServer = (AbstractNettyServer) AppContext.getBean(AppContext.FLASH_POLICY_SERVER);
            flashServer.startServer(flashPort);
            servers.add(flashServer);
        }

        if (tcpPort > 0) {
            AbstractNettyServer tcpServer = (AbstractNettyServer) AppContext.getBean(AppContext.TCP_SERVER);
            tcpServer.startServer(tcpPort);
            servers.add(tcpServer);
        }

        if (httpPort > 0) {
            AbstractNettyServer httpServer = (AbstractNettyServer) AppContext.getBean(AppContext.HTTP_SERVER);
            httpServer.startServer(httpPort);
            servers.add(httpServer);
        }

        if (webSocketPort > 0) {
            AbstractNettyServer webSocketServer = (AbstractNettyServer) AppContext.getBean(AppContext.WEB_SOCKET_SERVER);
            webSocketServer.startServer();
            servers.add(webSocketServer);
        }
    }

    @Override
    public void startServers() throws Exception {
        AbstractNettyServer flashServer = (AbstractNettyServer) AppContext.getBean(AppContext.FLASH_POLICY_SERVER);
        flashServer.startServer();
        servers.add(flashServer);

        AbstractNettyServer tcpServer = (AbstractNettyServer) AppContext.getBean(AppContext.TCP_SERVER);
        tcpServer.startServer();
        servers.add(tcpServer);

        AbstractNettyServer httpServer = (AbstractNettyServer) AppContext.getBean(AppContext.HTTP_SERVER);
        httpServer.startServer();
        servers.add(httpServer);

        AbstractNettyServer webSocketServer = (AbstractNettyServer) AppContext.getBean(AppContext.WEB_SOCKET_SERVER);
        webSocketServer.startServer();
        servers.add(webSocketServer);
    }

    @Override
    public void stopServers() throws Exception {
        for (AbstractNettyServer nettyServer : servers) {
            try {
                nettyServer.stopServer();
            } catch (Exception e) {
                LOG.error("Unable to stop server {} due to error {}", nettyServer, e);
                throw e;
            }
        }
    }
}
