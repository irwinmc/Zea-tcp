package com.akakata;

import com.akakata.context.ServerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example server that only runs HTTP (REST API mode).
 * Demonstrates selective server startup using configuration.
 *
 * <p>Configuration: src/test/resources/conf-http-only.properties
 *
 * <p>Usage:
 * <pre>
 * mvn test-compile
 * java -cp target/classes:target/test-classes com.akakata.HttpOnlyServer
 * </pre>
 *
 * @author Kelvin
 */
public class HttpOnlyServer extends CommandLine {

    private static final Logger LOG = LoggerFactory.getLogger(HttpOnlyServer.class);

    public static void main(String[] args) {
        new HttpOnlyServer().run(args);
    }

    @Override
    protected ServerContext createServerContext() throws Exception {
        // Use custom configuration that only enables HTTP
        String configPath = "conf-http-only.properties";
        LOG.info("Loading configuration: {}", configPath);
        return new ServerContext(configPath);
    }

    @Override
    protected void beforeServerStartup() throws Exception {
        LOG.info("╔═══════════════════════════════════════════════╗");
        LOG.info("║   HTTP-Only Server (REST API Mode)           ║");
        LOG.info("║   TCP: DISABLED                               ║");
        LOG.info("║   HTTP: ENABLED (port 18081)                  ║");
        LOG.info("║   WebSocket: DISABLED                         ║");
        LOG.info("╚═══════════════════════════════════════════════╝");
    }

    @Override
    protected void afterServerStartup() throws Exception {
        LOG.info("HTTP server ready");
        LOG.info("API endpoint: http://localhost:18081");
    }
}
