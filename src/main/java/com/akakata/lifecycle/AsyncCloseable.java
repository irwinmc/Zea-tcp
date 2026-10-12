package com.akakata.lifecycle;

import java.util.concurrent.CompletableFuture;

/**
 * Exposes a nonblocking shutdown request and observable cleanup completion.
 *
 * <p>A successful completion confirms cleanup. Failure preserves the cause; timing
 * out while waiting must not cancel cleanup or release resources still in use.
 *
 * @since 0.8.4
 */
public interface AsyncCloseable extends AutoCloseable {

    /**
     * Begins idempotent shutdown without waiting for an owned worker.
     *
     * @return an independent future observing cleanup completion
     */
    CompletableFuture<Void> closeAsync();

    /**
     * Requests shutdown and waits for a bounded interval outside managed workers.
     *
     * @throws ShutdownTimeoutException if cleanup exceeds the waiting deadline
     */
    @Override
    void close();
}
