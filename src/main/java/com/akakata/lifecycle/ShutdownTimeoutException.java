package com.akakata.lifecycle;

/**
 * Reports an expired shutdown wait without claiming that active work has stopped.
 *
 * @since 0.8.4
 */
public final class ShutdownTimeoutException extends IllegalStateException {

    /**
     * Serialization identifier.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Identifies the resource whose cleanup has not completed.
     *
     * @param resource      resource name
     * @param timeoutMillis maximum waiting interval in milliseconds
     */
    public ShutdownTimeoutException(String resource, long timeoutMillis) {
        super("Shutdown of " + resource + " exceeded " + timeoutMillis + " milliseconds; cleanup is still observable");
    }
}
