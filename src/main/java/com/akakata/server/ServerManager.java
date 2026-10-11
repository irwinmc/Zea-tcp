package com.akakata.server;

/**
 * Starts and stops the configured TCP, HTTP and WebSocket servers.
 *
 * @author Kelvin
 */
public interface ServerManager {

    /**
     * Starts the enabled servers at configured ports or at the supplied override ports.
     *
     * <p>The legacy Flash port parameter is retained for compatibility and is unused
     * by the default implementation.
     *
     * @param flashPort flash policy server port
     * @param tcpPort the TCP override port; nonpositive values skip the server
     * @param httpPort http port
     * @param webSocketPort web socket port
     * @throws Exception if an enabled server fails to start
     */
    void startServers(int flashPort, int tcpPort, int httpPort, int webSocketPort) throws Exception;

    /**
     * Starts the enabled servers at configured ports or at the supplied override ports.
     *
     * <p>The legacy Flash port parameter is retained for compatibility and is unused
     * by the default implementation.
     *
     * @throws Exception if an enabled server fails to start
     */
    void startServers() throws Exception;

    /**
     * Stops all server instances managed by this component.
     *
     * @throws Exception if server shutdown fails
     */
    void stopServers() throws Exception;
}
