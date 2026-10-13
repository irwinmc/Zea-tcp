package com.akakata.service.task;

import com.akakata.lifecycle.ShutdownSupport;
import com.akakata.lifecycle.AsyncCloseable;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs blocking application operations outside the ordered Session and Game executors.
 *
 * <p>Workers, pending tasks and caller-declared retained bytes are bounded. Rejection
 * completes exceptionally and never executes work on the submitting thread. Workers
 * must use bounded external-operation timeouts and return detached results. They must
 * not mutate Session or Game state. Submit results through the original event APIs;
 * completion order may differ from request order, so validate session identity and
 * operation sequence on the owning executor before applying a result.
 *
 * <p>Cancellation interrupts running work best-effort. Its byte and task charges
 * remain reserved until that work actually exits. Closure cancels queued work,
 * interrupts running work and waits for a bounded interval. Workers are daemon
 * threads so an uncooperative external library cannot prevent JVM exit.
 */
public final class BlockingTaskExecutor implements AsyncCloseable {

    /**
     * Default count of independent blocking workers.
     */
    public static final int DEFAULT_THREADS = 2;

    /**
     * Default maximum queued operations, excluding running workers.
     */
    public static final int DEFAULT_QUEUE_CAPACITY = 256;

    /**
     * Default estimated retained task storage of 64 MiB.
     */
    public static final long DEFAULT_QUEUE_BYTES = 64L * 1024 * 1024;

    /**
     * Default maximum shutdown wait in milliseconds.
     */
    public static final long DEFAULT_SHUTDOWN_MILLIS = 5_000;

    /**
     * Fixed worker pool with an explicitly bounded queue and rejection policy.
     */
    private final ThreadPoolExecutor executor;

    /**
     * Maximum pending and running operations combined.
     */
    private final long maximumTasks;

    /**
     * Maximum caller-declared retained bytes, including running operations.
     */
    private final long maximumBytes;

    /**
     * Maximum external close wait.
     */
    private final long shutdownMillis;

    /**
     * Accepted operations whose execution resources have not been released; guarded by this owner.
     */
    private final Set<Task<?>> tasks = new HashSet<>();

    /**
     * Total declared task bytes; guarded by this owner.
     */
    private long pendingBytes;

    /**
     * Terminal admission flag; guarded by this owner.
     */
    private boolean closed;

    /**
     * Successful completion confirms that the executor has actually terminated.
     */
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();

    /**
     * Creates a bounded worker pool; threads start lazily on the first submission.
     *
     * @param threads        the positive worker count
     * @param queueCapacity  the positive queued-operation allowance
     * @param maximumBytes   the positive declared-byte allowance
     * @param shutdownMillis the nonnegative maximum close wait
     * @throws IllegalArgumentException if a limit is invalid
     */
    public BlockingTaskExecutor(int threads, int queueCapacity, long maximumBytes, long shutdownMillis) {
        if (threads <= 0 || queueCapacity <= 0 || maximumBytes <= 0 || shutdownMillis < 0) {
            throw new IllegalArgumentException("Invalid blocking task limits");
        }
        this.maximumTasks = (long) threads + queueCapacity;
        this.maximumBytes = maximumBytes;
        this.shutdownMillis = shutdownMillis;
        AtomicInteger sequence = new AtomicInteger();
        executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), ShutdownSupport.workerFactory(runnable -> {
            Thread thread = new Thread(runnable, "zea-blocking-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }), new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Submits a detached blocking operation without waiting for queue capacity.
     *
     * <p>The declared size must include captured inputs and the maximum working or
     * returned data retained by the operation. Native inputs must be copied before
     * submission; this executor does not retain or release application buffers.
     * Future continuations may run on a worker or a cancelling caller. A timeout
     * applied to the future does not release a still-running task's allowance.
     *
     * @param <T>            the detached result type
     * @param estimatedBytes the nonnegative maximum retained size declared by the caller
     * @param operation      the bounded blocking operation
     * @return the completion, exceptionally completed on admission rejection
     * @throws IllegalArgumentException if the estimate is negative
     */
    public <T> CompletableFuture<T> submit(long estimatedBytes, Callable<T> operation) {
        Objects.requireNonNull(operation);
        if (estimatedBytes < 0) {
            throw new IllegalArgumentException("Task estimate must be nonnegative");
        }
        Task<T> task = new Task<>(estimatedBytes, operation);
        synchronized (this) {
            if (closed || tasks.size() >= maximumTasks || estimatedBytes > maximumBytes - pendingBytes) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("Blocking task budget exhausted or closed"));
            }
            tasks.add(task);
            pendingBytes += estimatedBytes;
            try {
                executor.execute(task);
            } catch (RejectedExecutionException rejected) {
                task.discard(rejected);
            }
        }
        return task.result;
    }

    /**
     * Returns tasks still retaining execution resources, including cancelled running work.
     *
     * @return the admitted operation count
     */
    public synchronized int pendingTasks() {
        return tasks.size();
    }

    /**
     * Returns bytes still reserved by accepted operations.
     *
     * @return estimated retained operation bytes
     */
    public synchronized long pendingBytes() {
        return pendingBytes;
    }

    /**
     * Reports admission availability independently of worker termination.
     *
     * @return whether closure has been requested
     */
    public synchronized boolean isClosed() {
        return closed;
    }

    /**
     * Reports whether every owned worker has stopped after closure.
     *
     * @return true only when the worker pool has terminated
     */
    public boolean isTerminated() {
        return executor.isTerminated();
    }

    /**
     * Captures worker limits and live reservations without waiting for running work.
     *
     * @return scalar limits and live gauges suitable for monitoring
     */
    public synchronized java.util.Map<String, Long> snapshot() {
        return java.util.Map.of("threads", (long) executor.getCorePoolSize(),
                "queue_capacity", maximumTasks - executor.getCorePoolSize(),
                "byte_budget", maximumBytes, "pending_tasks", (long) tasks.size(),
                "pending_bytes", pendingBytes, "active_workers", (long) executor.getActiveCount(),
                "closed", closed ? 1L : 0L, "terminated", executor.isTerminated() ? 1L : 0L);
    }

    /**
     * Stops admission, cancels queued work and requests interruption of running work.
     *
     * <p>Running operations retain their charges until they actually return. A zero
     * waiting interval requests shutdown only. Other external callers observe actual
     * termination for the configured interval; managed workers never wait.
     *
     * @throws com.akakata.lifecycle.ShutdownTimeoutException if the positive wait expires
     * @throws IllegalStateException if shutdown coordination fails
     */
    @Override
    public void close() {
        CompletableFuture<Void> completion = closeAsync();
        if (shutdownMillis > 0) {
            ShutdownSupport.await(completion, shutdownMillis, "blocking workers");
        }
    }

    /**
     * Stops admission and requests interruption, then observes actual executor exit.
     *
     * <p>Running operations retain their reservations until they leave. Observation
     * cancellation cannot cancel cleanup or publish a false termination signal.
     * Cancellation continuations use nonblocking reentrant shutdown semantics.
     * The termination wait runs on a dedicated cleanup thread.
     *
     * @return an independent future completed only after actual termination
     * @since 0.8.5
     */
    @Override
    public CompletableFuture<Void> closeAsync() {
        Task<?>[] outstanding;
        synchronized (this) {
            if (closed) {
                return closeCompletion.copy();
            }
            closed = true;
            outstanding = tasks.toArray(Task<?>[]::new);
            executor.shutdownNow();
        }
        for (Task<?> task : outstanding) {
            ShutdownSupport.runNonBlocking(() -> task.result.cancel(true));
        }
        ShutdownSupport.runAsync("zea-blocking-shutdown", () -> {
            boolean interrupted = false;
            try {
                while (!executor.isTerminated()) {
                    try {
                        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                    } catch (InterruptedException interruption) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }).whenComplete((ignored, failure) -> ShutdownSupport.complete(closeCompletion, failure));
        return closeCompletion.copy();
    }

    /**
     * Returns one task's declared resources exactly once.
     *
     * @param task the completed or discarded operation
     */
    private synchronized void release(Task<?> task) {
        if (tasks.remove(task)) {
            pendingBytes -= task.bytes;
        }
    }

    /**
     * Captures one bounded operation and separates future completion from actual resource release.
     *
     * @param <T> the operation result type
     */
    private final class Task<T> implements Runnable {

        /**
         * Accepted byte reservation.
         */
        private final long bytes;

        /**
         * Detached operation, cleared when execution or queued cancellation finishes.
         */
        private volatile Callable<T> operation;

        /**
         * Public completion, which may finish before a cancelled operation exits.
         */
        private final CompletableFuture<T> result = new CompletableFuture<>();

        /**
         * State zero is queued, one is running and two is released.
         */
        private final AtomicInteger state = new AtomicInteger();

        /**
         * Running worker, used for best-effort interruption.
         */
        private volatile Thread runner;

        /**
         * Captures an operation and connects cancellation to its execution state.
         *
         * @param bytes     the declared reservation
         * @param operation the detached blocking action
         */
        private Task(long bytes, Callable<T> operation) {
            this.bytes = bytes;
            this.operation = operation;
            result.whenComplete((value, failure) -> {
                if (!result.isCancelled()) {
                    return;
                }
                if (state.compareAndSet(0, 2)) {
                    executor.remove(this);
                    this.operation = null;
                    release(this);
                } else {
                    synchronized (this) {
                        if (state.get() == 1 && runner != null) {
                            runner.interrupt();
                        }
                    }
                }
            });
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void run() {
            synchronized (this) {
                if (!state.compareAndSet(0, 1)) {
                    return;
                }
                runner = Thread.currentThread();
            }
            T value = null;
            Throwable failure = null;
            try {
                if (!result.isDone()) {
                    value = operation.call();
                }
            } catch (Throwable thrown) {
                failure = thrown;
            } finally {
                operation = null;
                synchronized (this) {
                    runner = null;
                    state.set(2);
                }
                release(this);
            }
            if (failure == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(failure);
            }
        }

        /**
         * Frees a task whose pool admission failed after reservation.
         *
         * @param failure the executor rejection
         */
        private void discard(Throwable failure) {
            if (state.compareAndSet(0, 2)) {
                operation = null;
                release(this);
                result.completeExceptionally(failure);
            }
        }
    }
}
