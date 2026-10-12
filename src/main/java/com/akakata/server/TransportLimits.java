package com.akakata.server;

/**
 * Defines admission shared by all listeners and TCP/WebSocket input lifetime limits.
 *
 * @param maximumConnections maximum accepted sockets including unauthenticated clients
 * @param loginTimeoutMillis positive deadline covering connection, upgrade and authentication
 * @param readIdleMillis     read inactivity deadline; zero disables idle expiry
 * @param maximumFrameBytes  maximum application frame bytes, between 8 and 65535
 */
public record TransportLimits(int maximumConnections, int loginTimeoutMillis,
                              int readIdleMillis, int maximumFrameBytes) {

    /**
     * Default connection policy: 20000 sockets, 30-second login and 120-second read idle.
     */
    public static final TransportLimits DEFAULT = new TransportLimits(20_000, 30_000, 120_000, 65_535);

    /**
     * Validates all limits before opening a listener.
     *
     * @throws IllegalArgumentException if a limit is outside its documented range
     */
    public TransportLimits {
        if (maximumConnections <= 0 || loginTimeoutMillis <= 0 || readIdleMillis < 0
                || maximumFrameBytes < 8 || maximumFrameBytes > 65_535) {
            throw new IllegalArgumentException("Invalid transport limits");
        }
    }
}
