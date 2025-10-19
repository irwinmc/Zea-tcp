package com.akakata;

import com.akakata.context.ServerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example server that only runs WebSocket.
 * Demonstrates selective server startup using configuration.
 *
 * <p>Configuration: src/test/resources/conf-websocket-only.properties
 *
 * <p>Usage:
 * <pre>
 * mvn test-compile
 * java -cp target/classes:target/test-classes com.akakata.WebSocketOnlyServer
 * </pre>
 *
 * @author Kelvin
 */
public class WebSocketOnlyServer extends CommandLine {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketOnlyServer.class);

    public static void main(String[] args) {
        new WebSocketOnlyServer().run(args);
    }

    @Override
    protected ServerContext createServerContext() throws Exception {
        // Use custom configuration that only enables WebSocket
        String configPath = "conf-websocket-only.properties";
        LOG.info("Loading configuration: {}", configPath);
        return new ServerContext(configPath);
    }

    @Override
    protected void beforeServerStartup() throws Exception {
        LOG.info("╔═══════════════════════════════════════════════╗");
        LOG.info("║   WebSocket-Only Server                       ║");
        LOG.info("║   TCP: DISABLED                               ║");
        LOG.info("║   HTTP: DISABLED                              ║");
        LOG.info("║   WebSocket: ENABLED (port 18300)             ║");
        LOG.info("╚═══════════════════════════════════════════════╝");
    }

    @Override
    protected void afterServerStartup() throws Exception {
        LOG.info("WebSocket server ready");
        LOG.info("Connect to: ws://localhost:18300");
    }
}
