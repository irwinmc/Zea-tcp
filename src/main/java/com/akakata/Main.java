package com.akakata;

import com.akakata.context.AppContext;
import com.akakata.context.ServerContext;
import com.akakata.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for Zea-tcp game server.
 * Initializes ServerContext and starts all servers.
 *
 * @author Kelvin
 */
public class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        LOG.info("Starting Zea-tcp server...");

        ServerContext context = null;
        try {
            // Create and initialize server context
            context = new ServerContext();
            context.initialize();
            AppContext.setServerContext(context);

            LOG.info("ServerContext initialized successfully");

            // Start all servers
            startServers(context);

            // Add shutdown hook for graceful shutdown
            final ServerContext finalContext = context;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                LOG.info("Shutdown signal received, stopping servers...");
                stopServers(finalContext);
                finalContext.close();
                LOG.info("Zea-tcp server stopped");
            }, "shutdown-hook"));

            LOG.info("Zea-tcp server started successfully. Press Ctrl+C to stop.");

            // Keep main thread alive
            Thread.currentThread().join();

        } catch (Exception e) {
            LOG.error("Failed to start Zea-tcp server", e);
            if (context != null) {
                context.close();
            }
            System.exit(1);
        }
    }

    private static void startServers(ServerContext context) {
        // Start TCP server
        try {
            Server tcpServer = context.getBean(AppContext.TCP_SERVER, Server.class);
            tcpServer.startServer();
            LOG.info("TCP server started");
        } catch (Exception e) {
            LOG.error("Failed to start TCP server", e);
            throw new RuntimeException("TCP server startup failed", e);
        }

        // Start HTTP server
        try {
            Server httpServer = context.getBean(AppContext.HTTP_SERVER, Server.class);
            httpServer.startServer();
            LOG.info("HTTP server started");
        } catch (Exception e) {
            LOG.error("Failed to start HTTP server", e);
            throw new RuntimeException("HTTP server startup failed", e);
        }

        // Start WebSocket server
        try {
            Server wsServer = context.getBean(AppContext.WEB_SOCKET_SERVER, Server.class);
            wsServer.startServer();
            LOG.info("WebSocket server started");
        } catch (Exception e) {
            LOG.error("Failed to start WebSocket server", e);
            throw new RuntimeException("WebSocket server startup failed", e);
        }
    }

    private static void stopServers(ServerContext context) {
        // Stop servers gracefully
        try {
            Server tcpServer = context.getBean(AppContext.TCP_SERVER, Server.class);
            if (tcpServer != null) {
                tcpServer.stopServer();
                LOG.info("TCP server stopped");
            }
        } catch (Exception e) {
            LOG.error("Error stopping TCP server", e);
        }

        try {
            Server httpServer = context.getBean(AppContext.HTTP_SERVER, Server.class);
            if (httpServer != null) {
                httpServer.stopServer();
                LOG.info("HTTP server stopped");
            }
        } catch (Exception e) {
            LOG.error("Error stopping HTTP server", e);
        }

        try {
            Server wsServer = context.getBean(AppContext.WEB_SOCKET_SERVER, Server.class);
            if (wsServer != null) {
                wsServer.stopServer();
                LOG.info("WebSocket server stopped");
            }
        } catch (Exception e) {
            LOG.error("Error stopping WebSocket server", e);
        }
    }
}
