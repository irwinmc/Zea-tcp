package com.akakata.server;

import java.net.InetSocketAddress;

/**
 * @author Kelvin
 */
public interface Server {

    /**
     * Start server
     *
     * @throws Exception e
     */
    void startServer() throws Exception;

    /**
     * Start server with port
     *
     * @param port listening port
     * @throws Exception e
     */
    void startServer(int port) throws Exception;

    /**
     * Start server with socket address
     *
     * @param socketAddress socket address
     * @throws Exception e
     */
    void startServer(InetSocketAddress socketAddress) throws Exception;

    /**
     * Stop server
     *
     * @throws Exception e
     */
    void stopServer() throws Exception;

    /**
     * Get socket address
     *
     * @return socket address
     */
    InetSocketAddress getSocketAddress();
}
