package com.akakata.server;

/**
 * Bounds static-file handles independently of encoded socket buffer allowances.
 *
 * @param maximumPendingPerConnection positive file-transfer allowance per connection
 * @param maximumConcurrentTransfers positive aggregate allowance per handler owner
 * @param timeoutMillis positive absolute response-transfer deadline in milliseconds
 * @since 0.8.5
 */
public record HttpFileTransferLimits(int maximumPendingPerConnection, int maximumConcurrentTransfers,
                                     long timeoutMillis) {

    /**
     * Default policy: two transfers per connection, 128 total and a 30-second deadline.
     */
    public static final HttpFileTransferLimits DEFAULT = new HttpFileTransferLimits(2, 128, 30_000);

    /**
     * Validates file-transfer admission before constructing a listener.
     *
     * @param maximumPendingPerConnection positive per-connection allowance
     * @param maximumConcurrentTransfers positive aggregate allowance
     * @param timeoutMillis positive transfer deadline
     * @throws IllegalArgumentException if a limit is not positive
     */
    public HttpFileTransferLimits {
        if (maximumPendingPerConnection <= 0 || maximumConcurrentTransfers <= 0 || timeoutMillis <= 0) {
            throw new IllegalArgumentException("HTTP file-transfer limits must be positive");
        }
    }
}
