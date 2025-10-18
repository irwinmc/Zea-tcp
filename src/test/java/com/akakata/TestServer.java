package com.akakata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test server launcher for development and testing.
 * This class demonstrates how to extend CommandLine for custom server implementations.
 *
 * <p>Usage:
 * <pre>
 * mvn compile
 * java -cp target/Zea-tcp-1.0-SNAPSHOT.jar com.akakata.TestServer
 * </pre>
 *
 * @author Kelvin
 */
public class TestServer extends CommandLine {

    private static final Logger LOG = LoggerFactory.getLogger(TestServer.class);

    public static void main(String[] args) {
        new TestServer().run(args);
    }

    @Override
    protected void beforeServerStartup() throws Exception {
        LOG.info("=================================================");
        LOG.info("  Test Server - Development Mode");
        LOG.info("=================================================");
        LOG.info("Initializing test environment...");

        // Add your test initialization here
        // Example: Load test data, mock services, etc.
    }

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

    @Override
    protected void beforeServerShutdown() {
        LOG.info("Cleaning up test environment...");

        // Add your cleanup logic here
        // Example: Save test results, close test connections, etc.
    }

    @Override
    protected void afterServerShutdown() {
        LOG.info("Test server shutdown complete");
    }
}
