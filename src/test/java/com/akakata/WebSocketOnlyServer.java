package com.akakata;

import com.akakata.context.ServerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the example application with only the WebSocket server enabled.
 *
 * @author Kelvin
 */
public class WebSocketOnlyServer extends CommandLine {

    /**
     * Creates a web socket only server instance.
     */
    public WebSocketOnlyServer() {
    }

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketOnlyServer.class);

    /**
     * Runs this executable example or isolated regression probe.
     *
     * @param args the command-line arguments
     */
    public static void main(String[] args) {
        new WebSocketOnlyServer().run(args);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ServerContext createServerContext() throws Exception {
        // Use custom configuration that only enables WebSocket
        String configPath = "conf-websocket-only.properties";
        LOG.info("Loading configuration: {}", configPath);
        return new ServerContext(configPath);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void beforeServerStartup() throws Exception {
        LOG.info("╔═══════════════════════════════════════════════╗");
        LOG.info("║   WebSocket-Only Server                       ║");
        LOG.info("║   TCP: DISABLED                               ║");
        LOG.info("║   HTTP: DISABLED                              ║");
        LOG.info("║   WebSocket: ENABLED (port 18300)             ║");
        LOG.info("╚═══════════════════════════════════════════════╝");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void afterServerStartup() throws Exception {
        LOG.info("WebSocket server ready");
        LOG.info("Connect to: ws://localhost:18300");
    }
}
