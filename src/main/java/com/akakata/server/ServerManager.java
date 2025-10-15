package com.akakata.server;

/**
 * @author Kelvin
 */
public interface ServerManager {

    /**
     * Start servers with several ports
     *
     * @param flashPort     flash policy server port
     * @param tcpPort       tcp port
     * @param httpPort      http port
     * @param webSocketPort web socket port
     * @throws Exception
     */
    void startServers(int flashPort, int tcpPort, int httpPort, int webSocketPort) throws Exception;

    /**
     * Start servers with port in Spring xml
     *
     * @throws Exception
     */
    void startServers() throws Exception;

    /**
     * Used to stop the server and manage cleanup of zeal.
     *
     * @throws Exception
     */
    void stopServers() throws Exception;
}
