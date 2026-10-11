package com.akakata;

import com.akakata.context.ServerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the example application with only the HTTP server enabled.
 *
 * @author Kelvin
 */
public class HttpOnlyServer extends CommandLine {

    /**
     * Creates a http only server instance.
     */
    public HttpOnlyServer() {
    }

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(HttpOnlyServer.class);

    /**
     * Runs this executable example or isolated regression probe.
     *
     * @param args the command-line arguments
     */
    public static void main(String[] args) {
        new HttpOnlyServer().run(args);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ServerContext createServerContext() throws Exception {
        // Use custom configuration that only enables HTTP
        String configPath = "conf-http-only.properties";
        LOG.info("Loading configuration: {}", configPath);
        return new ServerContext(configPath);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void beforeServerStartup() throws Exception {
        LOG.info("╔═══════════════════════════════════════════════╗");
        LOG.info("║   HTTP-Only Server (REST API Mode)           ║");
        LOG.info("║   TCP: DISABLED                               ║");
        LOG.info("║   HTTP: ENABLED (port 18081)                  ║");
        LOG.info("║   WebSocket: DISABLED                         ║");
        LOG.info("╚═══════════════════════════════════════════════╝");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void afterServerStartup() throws Exception {
        LOG.info("HTTP server ready");
        LOG.info("API endpoint: http://localhost:18081");
    }
}
