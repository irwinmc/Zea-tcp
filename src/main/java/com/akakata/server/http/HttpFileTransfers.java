package com.akakata.server.http;

import com.akakata.metrics.FlowControlMetrics;
import com.akakata.server.HttpFileTransferLimits;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Owns static-file admission and absolute completion deadlines across HTTP connections.
 *
 * <p>Acquire before opening a file. Each lease is confined to its connection's event
 * loop and owns the opened handle until both file and response writes finish, fail
 * or the channel closes. Netty retains ownership of submitted file-region references.
 * Disk file sizes are not charged as resident encoded socket buffers.
 *
 * @since 0.8.5
 */
public final class HttpFileTransfers {

    /**
     * Logger for native file-handle closure failures.
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
     * Aggregate reservations, including files being opened and active writes.
     */
    private int pending;

    /**
     * Creates a shared file-transfer owner without starting additional executors.
     *
     * @param limits positive admission limits and transfer deadline
     */
    public HttpFileTransfers(HttpFileTransferLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /**
     * Reserves one handle before the file is opened.
     *
     * <p>The caller must close the lease after an open failure or a bodyless response.
     * An attached lease automatically closes on transfer failure or channel closure.
     *
     * @param channel active connection, called from its event loop
     * @return a lease, or null when the connection is closed or either quota is full
     */
    public synchronized Lease tryAcquire(Channel channel) {
        Objects.requireNonNull(channel, "channel");
        int count = connections.getOrDefault(channel, 0);
        if (!channel.isActive() || pending >= limits.maximumConcurrentTransfers()
                || count >= limits.maximumPendingPerConnection()) {
            FlowControlMetrics.HTTP_FILE_TRANSFER_REJECTIONS.increment();
            return null;
        }
        connections.put(channel, count + 1);
        pending++;
        FlowControlMetrics.PENDING_HTTP_FILE_TRANSFERS.increment();
        return new Lease(channel);
    }

    /**
     * Reports active reservations including the current response.
     *
     * @return total reserved transfers
     */
    public synchronized int pendingTransfers() {
        return pending;
    }

    /**
     * Returns the validated policy for this shared owner.
     *
     * @return file-transfer policy
     */
    public HttpFileTransferLimits limits() {
        return limits;
    }

    /**
     * Returns one reservation after its owned handle has closed.
     *
     * @param channel connection owning the reservation
     */
    private synchronized void release(Channel channel) {
        int count = connections.get(channel);
        if (count == 1) {
            connections.remove(channel);
        } else {
            connections.put(channel, count - 1);
        }
        pending--;
        FlowControlMetrics.PENDING_HTTP_FILE_TRANSFERS.decrement();
    }

    /**
     * Owns one file handle and its deadline on the connection's event loop.
     */
    public final class Lease implements AutoCloseable {

        /**
         * Connection used for cancellation and monotonic deadline scheduling.
         */
        private final Channel channel;

        /**
         * Admission time, so new incoming requests cannot renew this transfer.
         */
        private final long started = System.nanoTime();

        /**
         * Channel-close listener removed when this lease completes.
         */
        private final ChannelFutureListener channelClosed = ignored -> close();

        /**
         * Attached handle, or null before file opening has completed.
         */
        private FileChannel file;

        /**
         * Absolute response-transfer deadline, cancelled after actual cleanup.
         */
        private ScheduledFuture<?> deadline;

        /**
         * Completed write count; both the file and response terminator must finish.
         */
        private int completedWrites;

        /**
         * Idempotent reservation-release flag.
         */
        private boolean released;

        /**
         * Captures the admitted connection without opening the resource.
         *
         * @param channel active admitted connection
         */
        private Lease(Channel channel) {
            this.channel = channel;
        }

        /**
         * Takes ownership of an opened file and arms its absolute transfer deadline.
         *
         * @param file opened handle whose ownership is transferred to this lease
         * @throws IllegalStateException if the lease is closed or already owns a file
         */
        public void attach(FileChannel file) {
            if (released || this.file != null) {
                throw new IllegalStateException("File-transfer lease is closed or already attached");
            }
            this.file = Objects.requireNonNull(file, "file");
            channel.closeFuture().addListener(channelClosed);
            if (!released) {
                long elapsed = System.nanoTime() - started;
                long remaining = Math.max(0, TimeUnit.MILLISECONDS.toNanos(limits.timeoutMillis()) - elapsed);
                deadline = channel.eventLoop().schedule(() -> {
                    if (!released) {
                        FlowControlMetrics.HTTP_FILE_TRANSFER_TIMEOUTS.increment();
                        channel.close();
                        close();
                    }
                }, remaining, TimeUnit.NANOSECONDS);
            }
        }

        /**
         * Watches both accepted writes while preserving their submission order.
         *
         * @param transfer file-region write completion
         * @param finish final HTTP content write completion
         */
        public void watch(ChannelFuture transfer, ChannelFuture finish) {
            transfer.addListener(this::writeCompleted);
            finish.addListener(this::writeCompleted);
        }

        /**
         * Keeps ownership until both writes finish and aborts the connection on failure.
         *
         * @param write observed response write
         */
        private void writeCompleted(io.netty.util.concurrent.Future<? super Void> write) {
            if (!write.isSuccess()) {
                channel.close();
                close();
            } else if (++completedWrites == 2) {
                close();
            }
        }

        /**
         * Closes the handle before returning its reservation, exactly once.
         *
         * <p>Submitted file-region reference counts remain the outbound pipeline's
         * responsibility. Closing a channel discards those writes through Netty.
         */
        @Override
        public void close() {
            if (released) {
                return;
            }
            if (file != null) {
                try {
                    file.close();
                } catch (IOException failure) {
                    LOG.warn("Failed to close HTTP file-transfer handle", failure);
                    if (file.isOpen()) {
                        return;
                    }
                }
            }
            released = true;
            if (deadline != null) {
                deadline.cancel(false);
            }
            channel.closeFuture().removeListener(channelClosed);
            release(channel);
        }
    }
}
