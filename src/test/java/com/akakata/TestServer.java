package com.akakata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the default game-server configuration as an executable example.
 *
 * @author Kelvin
 */
public class TestServer extends CommandLine {

    /**
     * Creates a test server instance.
     */
    public TestServer() {
    }

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(TestServer.class);

    /**
     * Runs this executable example or isolated regression probe.
     *
     * @param args the command-line arguments
     */
    public static void main(String[] args) {
        new TestServer().run(args);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void beforeServerStartup() throws Exception {
        LOG.info("=================================================");
        LOG.info("  Test Server - Development Mode");
        LOG.info("=================================================");
        LOG.info("Initializing test environment...");

        // Add your test initialization here
        // Example: Load test data, mock services, etc.
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void afterServerStartup() throws Exception {
        LOG.info("Test server is ready for connections");
        LOG.info("TCP Server: localhost:8090");
        LOG.info("HTTP Server: localhost:8081");
        LOG.info("WebSocket Server: localhost:8300");
        LOG.info("=================================================");

        // Add your post-startup logic here
        // Example: Start test clients, run health checks, etc.
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void beforeServerShutdown() {
        LOG.info("Cleaning up test environment...");

        // Add your cleanup logic here
        // Example: Save test results, close test connections, etc.
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void afterServerShutdown() {
        LOG.info("Test server shutdown complete");
    }
}
