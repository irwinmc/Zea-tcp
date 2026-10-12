package com.akakata.lifecycle;

import java.util.Objects;
import java.util.concurrent.*;

/**
 * Applies a common shutdown-wait policy without depending on application services.
 *
 * @since 0.8.4
 */
public final class ShutdownSupport {

    /**
     * Default external waiting interval for standalone resources.
     */
    public static final long DEFAULT_TIMEOUT_MILLIS = 5_000;

    /**
     * Marks framework workers that must never wait for another owned executor.
     */
    private static final ThreadLocal<Boolean> MANAGED_WORKER = new ThreadLocal<>();

    /**
     * Marks cleanup actions that may reenter their owning context.
     */
    private static final ThreadLocal<Boolean> CLEANUP_ACTION = new ThreadLocal<>();

    /**
     * Prevents construction of this utility.
     */
    private ShutdownSupport() {
    }

    /**
     * Preserves a delegate's thread type while marking its execution context.
     *
     * @param delegate thread factory supplying names and daemon policy
     * @return a factory marking the entire worker lifetime
     */
    public static ThreadFactory workerFactory(ThreadFactory delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return task -> delegate.newThread(() -> {
            MANAGED_WORKER.set(true);
            try {
                task.run();
            } finally {
                MANAGED_WORKER.remove();
            }
        });
    }

    /**
     * Identifies callers that must request cleanup without blocking.
     *
     * @return whether the caller is a framework worker
     */
    public static boolean isManagedWorker() {
        return Boolean.TRUE.equals(MANAGED_WORKER.get());
    }

    /**
     * Identifies cleanup actions that must not wait for their own context sequence.
     *
     * @return whether the caller is running a cleanup action
     */
    public static boolean isCleanupAction() {
        return Boolean.TRUE.equals(CLEANUP_ACTION.get());
    }

    /**
     * Signals completion while preventing inline continuations from joining framework workers.
     *
     * <p>Netty termination notifications can run on a shared completion executor.
     * Reentrant shutdown requests from their continuations must remain nonblocking.
     *
     * @param completion internal completion signal
     * @param failure    cleanup failure, or null for success
     */
    public static void complete(CompletableFuture<Void> completion, Throwable failure) {
        runNonBlocking(() -> {
            if (failure == null) {
                completion.complete(null);
            } else {
                completion.completeExceptionally(failure);
            }
        });
    }

    /**
     * Runs a lifecycle callback with nonblocking reentrant shutdown semantics.
     *
     * @param callback callback that must not join an executor or its closing owner
     */
    public static void runNonBlocking(Runnable callback) {
        Boolean previous = MANAGED_WORKER.get();
        MANAGED_WORKER.set(true);
        try {
            callback.run();
        } finally {
            if (previous == null) {
                MANAGED_WORKER.remove();
            } else {
                MANAGED_WORKER.set(previous);
            }
        }
    }

    /**
     * Waits outside workers using a monotonic deadline and preserving interruption.
     *
     * <p>The observed future is never cancelled or modified. Managed workers return
     * immediately; their owner can observe the same cleanup from another thread.
     *
     * @param completion    actual cleanup completion
     * @param timeoutMillis positive external waiting interval
     * @param resource      diagnostic resource name
     * @throws ShutdownTimeoutException if the deadline expires
     * @throws IllegalStateException    if cleanup fails
     */
    public static void await(CompletableFuture<?> completion, long timeoutMillis, String resource) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("Shutdown waiting interval must be positive");
        }
        if (isManagedWorker()) {
            return;
        }
        long budget = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long started = System.nanoTime();
        boolean interrupted = false;
        try {
            while (true) {
                long remaining = Math.max(0, budget - (System.nanoTime() - started));
                try {
                    completion.get(remaining, TimeUnit.NANOSECONDS);
                    return;
                } catch (InterruptedException interruption) {
                    interrupted = true;
                } catch (TimeoutException timeout) {
                    throw new ShutdownTimeoutException(resource, timeoutMillis);
                } catch (ExecutionException failure) {
                    throw new IllegalStateException("Shutdown failed for " + resource, failure.getCause());
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Invokes potentially blocking application cleanup on its own virtual thread.
     *
     * @param name   diagnostic thread name
     * @param action cleanup action
     * @return actual action completion, including failures
     */
    public static CompletableFuture<Void> runAsync(String name, Runnable action) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        try {
            Thread.ofVirtual().name(name).start(() -> {
                CLEANUP_ACTION.set(true);
                try {
                    action.run();
                    complete(completion, null);
                } catch (Throwable failure) {
                    complete(completion, failure);
                } finally {
                    CLEANUP_ACTION.remove();
                }
            });
        } catch (Throwable failure) {
            complete(completion, failure);
        }
        return completion;
    }
}
