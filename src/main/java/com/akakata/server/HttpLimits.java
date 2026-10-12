package com.akakata.server;

/**
 * Bounds HTTP aggregation and absolute request or read-inactivity deadlines.
 *
 * @param maximumRequestBytes positive maximum aggregated request body bytes
 * @param requestTimeoutMillis positive deadline for each complete request
 * @param readIdleMillis nonnegative read inactivity limit; zero disables idle expiry
 * @param maximumConnections positive HTTP-specific socket allowance within the shared total
 */
public record HttpLimits(int maximumRequestBytes, int requestTimeoutMillis, int readIdleMillis, int maximumConnections) {

    /**
     * Default policy: 256 sockets, 64 KiB bodies, 10-second requests and 30-second read inactivity.
     */
    public static final HttpLimits DEFAULT = new HttpLimits(65_536, 10_000, 30_000, 256);

    /**
     * Creates a request policy with a default allowance of 256 HTTP connections.
     *
     * @param maximumRequestBytes the positive body allowance
     * @param requestTimeoutMillis the positive request deadline
     * @param readIdleMillis the nonnegative read inactivity deadline
     */
    public HttpLimits(int maximumRequestBytes, int requestTimeoutMillis, int readIdleMillis) {
        this(maximumRequestBytes, requestTimeoutMillis, readIdleMillis, 256);
    }

    /**
     * Validates the HTTP policy before a listener is constructed.
     *
     * @param maximumRequestBytes the positive body allowance
     * @param requestTimeoutMillis the positive request deadline
     * @param readIdleMillis the nonnegative read inactivity deadline
     * @param maximumConnections the positive HTTP socket allowance
     * @throws IllegalArgumentException if a value is outside its supported range
     */
    public HttpLimits {
        if (maximumRequestBytes <= 0 || requestTimeoutMillis <= 0 || readIdleMillis < 0 || maximumConnections <= 0) {
            throw new IllegalArgumentException("Invalid HTTP limits");
        }
    }
}
