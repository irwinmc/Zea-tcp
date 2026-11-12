package com.akakata.server.http;

import com.akakata.lifecycle.AsyncCloseable;
import com.akakata.lifecycle.ShutdownSupport;
import com.akakata.metrics.FlowControlMetrics;
import com.akakata.server.HttpFileTransferLimits;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Bounds static-file admission and owns filesystem workers and transfer deadlines.
 *
 * <p>Reservations cover queued opening, filesystem work, transport writes and actual
 * handle closure. Opening and closing run on at most two lazy daemon workers. Each
 * reservation supplies its own cleanup capacity, so closing never falls back to an
 * I/O thread. Netty owns submitted file-region references, but leases own their files.
 */
public final class HttpFileTransfers implements AsyncCloseable {

    /**
     * Logger for file-operation failures.
     */
    private static final Logger LOG = LoggerFactory.getLogger(HttpFileTransfers.class);

    /**
     * Validated admission and deadline policy.
     */
    private final HttpFileTransferLimits limits;

    /**
     * Reserved transfer counts by connection identity, guarded by this owner.
     */
    private final Map<Channel, Integer> connections = new IdentityHashMap<>();

    /**
     * All unreleased reservations, including running filesystem operations.
     */
    private final Set<Lease> leases = new HashSet<>();

    /**
     * Actual worker termination, independent of observation cancellation.
     */
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();

    /**
     * Bounded filesystem executor; each live lease has at most one queued operation.
     */
    private final ThreadPoolExecutor workers;

    /**
     * Rejects new reservations after shutdown begins.
     */
    private boolean closed;

    /**
     * Creates a file-transfer owner whose workers start only on filesystem work.
     *
     * @param limits positive admission limits and transfer deadline
     */
    public HttpFileTransfers(HttpFileTransferLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        int threads = Math.min(2, limits.maximumConcurrentTransfers());
        AtomicInteger sequence = new AtomicInteger();
        workers = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(limits.maximumConcurrentTransfers()),
                ShutdownSupport.workerFactory(task -> {
                    Thread thread = new Thread(task, "zea-http-file-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }), new ThreadPoolExecutor.AbortPolicy()) {
            @Override
            protected void terminated() {
                ShutdownSupport.complete(closeCompletion, null);
            }
        };
        workers.allowCoreThreadTimeOut(true);
    }

    /**
     * Reserves capacity and starts a deadline before any filesystem work.
     *
     * @param channel active connection, normally called from its event loop
     * @return a lease, or null when closed or either quota is full
     */
    public Lease tryAcquire(Channel channel) {
        Objects.requireNonNull(channel, "channel");
        Lease lease;
        synchronized (this) {
            int count = connections.getOrDefault(channel, 0);
            if (closed || !channel.isActive() || leases.size() >= limits.maximumConcurrentTransfers()
                    || count >= limits.maximumPendingPerConnection()) {
                FlowControlMetrics.HTTP_FILE_TRANSFER_REJECTIONS.increment();
                return null;
            }
            lease = new Lease(channel);
            leases.add(lease);
            connections.put(channel, count + 1);
            FlowControlMetrics.PENDING_HTTP_FILE_TRANSFERS.increment();
        }
        lease.arm();
        return lease;
    }

    /**
     * Reports reservations through actual filesystem cleanup.
     *
     * @return total reserved transfers
     */
    public synchronized int pendingTransfers() {
        return leases.size();
    }

    /**
     * Returns this owner's admission and deadline policy.
     *
     * @return file-transfer policy
     */
    public HttpFileTransferLimits limits() {
        return limits;
    }

    /**
     * Stops admission, requests connection cleanup and observes worker termination.
     * Running opens retain their reservation and close late results before release.
     *
     * @return a cancellation-independent termination observation
     */
    @Override
    public CompletableFuture<Void> closeAsync() {
        ArrayList<Lease> outstanding;
        synchronized (this) {
            closed = true;
            outstanding = new ArrayList<>(leases);
            if (outstanding.isEmpty()) {
                workers.shutdown();
            }
        }
        for (Lease lease : outstanding) {
            lease.channel.close();
            lease.close();
        }
        return closeCompletion.thenApply(ignored -> null);
    }

    /**
     * Requests cleanup and waits outside framework workers for bounded termination.
     *
     * @throws com.akakata.lifecycle.ShutdownTimeoutException if actual cleanup stalls
     */
    @Override
    public void close() {
        ShutdownSupport.await(closeAsync(), ShutdownSupport.DEFAULT_TIMEOUT_MILLIS, "HTTP file workers");
    }

    /**
     * Returns capacity only after the lease has finished filesystem work.
     *
     * @param lease completed reservation
     */
    private synchronized void release(Lease lease) {
        if (!leases.remove(lease)) {
            return;
        }
        int count = connections.get(lease.channel);
        if (count == 1) {
            connections.remove(lease.channel);
        } else {
            connections.put(lease.channel, count - 1);
        }
        FlowControlMetrics.PENDING_HTTP_FILE_TRANSFERS.decrement();
        if (closed && leases.isEmpty()) {
            workers.shutdown();
        }
    }

    /**
     * Owns one admitted operation and its handle through actual cleanup.
     */
    public final class Lease implements AutoCloseable {

        /**
         * Connection used for response application and monotonic deadlines.
         */
        private final Channel channel;

        /**
         * Listener requesting cleanup after disconnection.
         */
        private final ChannelFutureListener channelClosed = ignored -> close();

        /**
         * Owned handle, published under this lease's monitor.
         */
        private FileChannel file;

        /**
         * Admission-to-completion timer, cancelled when cleanup is requested.
         */
        private ScheduledFuture<?> deadline;

        /**
         * Opening is queued or executing and must keep its reservation.
         */
        private boolean opening;

        /**
         * Terminal cleanup request, including disconnection and timeout.
         */
        private boolean closing;

        /**
         * Number of successful file and terminator writes.
         */
        private int completedWrites;

        /**
         * Captures the connection without touching the filesystem.
         *
         * @param channel admitted connection
         */
        private Lease(Channel channel) {
            this.channel = channel;
        }

        /**
         * Starts cancellation observation before an open can be queued.
         */
        private synchronized void arm() {
            if (closing) {
                return;
            }
            channel.closeFuture().addListener(channelClosed);
            if (!closing) {
                try {
                    deadline = channel.eventLoop().schedule(() -> {
                        synchronized (Lease.this) {
                            if (closing) {
                                return;
                            }
                            FlowControlMetrics.HTTP_FILE_TRANSFER_TIMEOUTS.increment();
                        }
                        channel.close();
                        close();
                    }, limits.timeoutMillis(), TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException rejected) {
                    close();
                }
            }
        }

        /**
         * Opens on a reserved worker and applies the result on the original I/O loop.
         *
         * @param opener detached operation returning an owned resource
         * @param completion response application; not invoked after closure
         */
        void open(Callable<StaticResourceResolver.Resource> opener,
                  BiConsumer<StaticResourceResolver.Resource, Throwable> completion) {
            synchronized (this) {
                if (closing) {
                    return;
                }
                if (opening || file != null) {
                    throw new IllegalStateException("File-transfer lease already owns an operation");
                }
                opening = true;
            }
            workers.execute(() -> openOnWorker(opener, completion));
        }

        /**
         * Captures late results for cleanup even when the connection has disappeared.
         *
         * @param opener owned file-opening operation
         * @param completion response application on the I/O loop
         */
        private void openOnWorker(Callable<StaticResourceResolver.Resource> opener,
                                  BiConsumer<StaticResourceResolver.Resource, Throwable> completion) {
            StaticResourceResolver.Resource resource = null;
            Throwable failure = null;
            try {
                synchronized (this) {
                    if (closing) {
                        opening = false;
                        release(this);
                        return;
                    }
                }
                resource = Objects.requireNonNull(opener.call(), "Resource opener returned null");
            } catch (Throwable thrown) {
                failure = thrown;
            }
            boolean discard;
            synchronized (this) {
                opening = false;
                file = resource == null ? null : resource.channel();
                discard = closing;
            }
            if (discard) {
                closeOnWorker();
                return;
            }
            StaticResourceResolver.Resource opened = resource;
            Throwable outcome = failure;
            try {
                channel.eventLoop().execute(() -> {
                    if (isClosing() || !channel.isActive()) {
                        close();
                        return;
                    }
                    completion.accept(opened, outcome);
                });
            } catch (RejectedExecutionException rejected) {
                close();
            }
        }

        /**
         * Tests whether response application is still permitted.
         *
         * @return whether cleanup has been requested
         */
        synchronized boolean isClosing() {
            return closing;
        }

        /**
         * Takes ownership of an externally opened handle; its deadline already runs.
         *
         * @param file handle transferred on success and caller-owned on rejection
         * @throws IllegalStateException if closed or already owning an operation
         */
        public synchronized void attach(FileChannel file) {
            if (closing || opening || this.file != null) {
                throw new IllegalStateException("File-transfer lease is closed or already attached");
            }
            this.file = Objects.requireNonNull(file, "file");
        }

        /**
         * Watches both writes; the lease keeps the file until both complete.
         *
         * @param transfer file-region write completion
         * @param finish final HTTP content write completion
         */
        public void watch(ChannelFuture transfer, ChannelFuture finish) {
            transfer.addListener(this::writeCompleted);
            finish.addListener(this::writeCompleted);
        }

        /**
         * Requests cleanup on write failure or after both successful writes.
         *
         * @param write observed response write
         */
        private synchronized void writeCompleted(io.netty.util.concurrent.Future<? super Void> write) {
            if (!write.isSuccess()) {
                channel.close();
                close();
            } else if (++completedWrites == 2) {
                close();
            }
        }

        /**
         * Requests idempotent worker cleanup without releasing a running open early.
         */
        @Override
        public void close() {
            boolean cleanup;
            synchronized (this) {
                if (closing) {
                    return;
                }
                closing = true;
                if (deadline != null) {
                    deadline.cancel(false);
                }
                channel.closeFuture().removeListener(channelClosed);
                if (opening) {
                    return;
                }
                cleanup = file != null;
            }
            if (cleanup) {
                workers.execute(this::closeOnWorker);
            } else {
                release(this);
            }
        }

        /**
         * Closes the owned handle before returning the reservation and metrics.
         */
        private void closeOnWorker() {
            try {
                if (file != null) {
                    file.close();
                }
            } catch (Exception failure) {
                LOG.warn("Failed to close HTTP file-transfer handle", failure);
            } finally {
                release(this);
            }
        }
    }
}
