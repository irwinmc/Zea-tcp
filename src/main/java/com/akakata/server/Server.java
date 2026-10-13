package com.akakata.server;

import java.net.InetSocketAddress;

/**
 * Defines bind, start and stop operations for a network server.
 *
 * @author Kelvin
 */
public interface Server {

    /**
     * Reports whether the bound listening channel is currently active.
     *
     * <p>Implementations must return promptly without waiting for bind or shutdown.
     * Custom implementations must override this fail-closed default to participate
     * in listener readiness checks.
     *
     * @return whether this server can accept new connections
     */
    default boolean isListening() {
        return false;
    }

    /**
     * Binds and starts this server at its configured or supplied address.
     *
     * @throws Exception if the server lifecycle operation fails
     */
    void startServer() throws Exception;

    /**
     * Binds and starts this server at its configured or supplied address.
     *
     * @param port listening port
     * @throws Exception if the server lifecycle operation fails
     */
    void startServer(int port) throws Exception;

    /**
     * Binds and starts this server at its configured or supplied address.
     *
     * @param socketAddress socket address
     * @throws Exception if the server lifecycle operation fails
     */
    void startServer(InetSocketAddress socketAddress) throws Exception;

    /**
     * Stops this server and releases its listening channel.
     *
     * @throws Exception if the server lifecycle operation fails
     */
    void stopServer() throws Exception;

    /**
     * Get socket address.
     *
     * @return socket address
     */
    InetSocketAddress getSocketAddress();
}
