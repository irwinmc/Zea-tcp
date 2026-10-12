package com.akakata.benchmark;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies real TCP/WebSocket pipelines in isolated JVMs with bounded completion deadlines.
 */
class NetworkTransportRegressionTest {

    /**
     * Creates the real-network fixture.
     */
    NetworkTransportRegressionTest() {}

    /**
     * Isolated process output directory.
     */
    @TempDir
    Path directory;

    /**
     * Runs wire-order, malformed-input, login/idle timeout and encoded-budget regressions.
     *
     * @param transport selected TCP or WebSocket transport
     * @throws Exception if the child JVM fails to complete or report successful verification
     */
    @ParameterizedTest
    @ValueSource(strings = {"tcp", "websocket"})
    @Timeout(25)
    void actualSocketPipelinesPreserveOrderAndReleaseRejectedClients(String transport) throws Exception {
        Path log = directory.resolve(transport + ".log");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, NetworkLoadTest.class.getName(), "--verify", "--transport=" + transport, "--socket-bytes=1024")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), () -> read(log));
            assertEquals(0, child.exitValue(), () -> read(log));
            assertTrue(read(log).contains("NETWORK_REGRESSION_OK " + transport), () -> read(log));
            assertTrue(read(log).contains("ServerContext shutdown complete"), () -> read(log));
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(2, TimeUnit.SECONDS); }
        }
    }

    /**
     * Reads diagnostic child-process output without masking a failed assertion.
     *
     * @param path output log path
     * @return captured diagnostics or the read failure message
     */
    private static String read(Path path) {
        try { return Files.readString(path); }
        catch (Exception failure) { return failure.toString(); }
    }
}
