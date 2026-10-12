package com.akakata.communication.impl;

import com.akakata.communication.MessageSender;
import com.akakata.communication.OutboundBudget;
import com.akakata.communication.OutboundLimits;
import com.akakata.event.Event;
import com.akakata.event.EventResources;
import com.akakata.event.Events;
import com.akakata.handlers.codec.*;
import com.akakata.metrics.FlowControlMetrics;
import com.akakata.util.NettyUtils;
import io.netty.channel.*;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends ordered socket writes with bounded admission and encoded-buffer accounting.
 *
 * <p>Event writes retain the native payload until the write future completes.
 * Writes always enter the channel event-loop queue in admission order. A full write
 * count, source, encoded or aggregate budget fails the write and closes the
 * connection. Channel water marks pause the bounded FIFO until writability returns.
 * An admitted write that exceeds its completion deadline also closes the connection.
 * This prevents later updates from following a rejected state update.
 * For raw Netty messages, normal pipeline ownership rules apply, including release
 * on admission rejection. Closing selects an
 * event encoder when available and otherwise writes a one-byte disconnect opcode.
 *
 * @author Kelvin
 */
public class SocketMessageSender implements MessageSender {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(SocketMessageSender.class);

    /**
     * Source-estimate threshold for an early batch flush, independent of hard budgets.
     */
    private static final long BATCH_BYTES = 32 * 1024;

    /**
     * Associated transport channel.
     */
    private final Channel channel;

    /**
     * Per-connection application-write and encoded-buffer limits.
     */
    private final OutboundLimits limits;

    /**
     * Shared source and encoded reservation owner for this application's sockets.
     */
    private final OutboundBudget budget;

    /**
     * Estimated admitted source bytes, including sources being encoded; guarded by this sender.
     */
    private long pendingPayloadBytes;

    /**
     * Admitted writes, including queued event-loop tasks; guarded by this sender.
     */
    private final Set<PendingWrite> pending = new LinkedHashSet<>();

    /**
     * FIFO admission order consumed in bounded batches by the channel event loop.
     */
    private final ArrayDeque<PendingWrite> queued = new ArrayDeque<>();

    /**
     * Whether a batch drain has been scheduled or is executing; guarded by this sender.
     */
    private boolean drainScheduled;

    /**
     * Positive maximum time from admission to transport completion.
     */
    private final long writeTimeoutNanos;

    /**
     * Single deadline task and its oldest outstanding write; guarded by this sender.
     */
    private ScheduledFuture<?> writeDeadline;

    /**
     * Write whose admission timestamp owns the current deadline; guarded by this sender.
     */
    private PendingWrite deadlineWrite;

    /**
     * Writability subscription installed on the event loop when writes first drain.
     */
    private final ChannelInboundHandlerAdapter recoveryHandler = new ChannelInboundHandlerAdapter() {
        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            resumeWrites();
            ctx.fireChannelWritabilityChanged();
        }
    };

    /**
     * Terminal sender flag published before overload or explicit closure.
     */
    private volatile boolean closed;

    /**
     * Associates an outbound sender with a Netty channel.
     *
     * @param channel the Netty transport channel
     */
    public SocketMessageSender(Channel channel) {
        this(channel, OutboundLimits.DEFAULT);
    }

    /**
     * Creates a sender with explicit per-connection outbound budgets.
     *
     * @param channel the transport channel
     * @param limits  the positive application-write and encoded-byte limits
     */
    public SocketMessageSender(Channel channel, OutboundLimits limits) {
        this(channel, limits, OutboundBudget.DEFAULT);
    }

    /**
     * Creates a sender sharing a context-wide source and encoded-byte allowance.
     *
     * @param channel the transport channel
     * @param limits  the per-connection admission limits
     * @param budget  the aggregate reservation owner
     */
    public SocketMessageSender(Channel channel, OutboundLimits limits, OutboundBudget budget) {
        this(channel, limits, budget, 5_000);
    }

    /**
     * Creates a sender with a bounded deadline covering queued and forwarded writes.
     *
     * @param channel            the transport channel
     * @param limits             the per-connection admission limits
     * @param budget             the aggregate reservation owner
     * @param maximumWriteMillis positive admission-to-completion deadline in milliseconds
     * @throws IllegalArgumentException if the deadline is not positive
     */
    public SocketMessageSender(Channel channel, OutboundLimits limits, OutboundBudget budget, long maximumWriteMillis) {
        if (maximumWriteMillis <= 0) {
            throw new IllegalArgumentException("Socket write deadline must be positive");
        }
        this.channel = Objects.requireNonNull(channel, "channel");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.writeTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(maximumWriteMillis);
        channel.closeFuture().addListener(ignored -> failPendingWrites());
    }

    /**
     * Admits an ordered message for a batched channel write and flush.
     *
     * <p>For an event, a captured native payload is retained until the write future
     * completes, including failed writes. The caller keeps its original event-payload
     * reference. Raw Netty messages follow normal pipeline ownership transfer.
     *
     * @param message the message or string to send
     * @return the channel write future
     */
    @Override
    public synchronized ChannelFuture sendMessage(Object message) {
        Objects.requireNonNull(message, "message");
        if (closed || !channel.isActive()) {
            releaseRawMessage(message);
            return channel.newFailedFuture(new ClosedChannelException());
        }
        long bytes;
        try {
            bytes = message instanceof Event event ? event.getPayloadSize()
                    : message instanceof io.netty.buffer.ByteBufHolder holder ? holder.content().readableBytes()
                    : EventResources.estimateBytes(message);
        } catch (RuntimeException failure) {
            releaseRawMessage(message);
            closed = true;
            channel.close();
            return channel.newFailedFuture(failure);
        }
        if (bytes < 0 || pending.size() >= limits.maximumPendingWrites()
                || bytes > limits.maximumPendingPayloadBytes() - pendingPayloadBytes || !budget.reserve(bytes)) {
            FlowControlMetrics.WRITE_REJECTIONS.increment();
            closed = true;
            releaseRawMessage(message);
            channel.close();
            return channel.newFailedFuture(new RejectedExecutionException("Socket write budget exceeded"));
        }
        Object payload;
        try {
            payload = message instanceof Event event ? EventResources.retainPayload(event) : null;
        } catch (RuntimeException failure) {
            budget.release(bytes);
            releaseRawMessage(message);
            closed = true;
            channel.close();
            return channel.newFailedFuture(failure);
        }
        ChannelPromise promise = channel.newPromise();
        promise.setUncancellable();
        PendingWrite write = new PendingWrite(message, payload, promise, bytes);
        pendingPayloadBytes += bytes;
        FlowControlMetrics.PENDING_PAYLOAD_BYTES.add(bytes);
        pending.add(write);
        queued.addLast(write);
        FlowControlMetrics.PENDING_WRITES.increment();
        promise.addListener(ignored -> completeWrite(write));
        if (!drainScheduled) {
            drainScheduled = true;
            scheduleDrain();
        }
        return promise;
    }

    /**
     * Schedules a single ordered drain and settles all ownership if the loop stops.
     */
    private void scheduleDrain() {
        try {
            channel.eventLoop().execute(this::drainWrites);
        } catch (Throwable failure) {
            PendingWrite[] writes;
            synchronized (this) {
                closed = true;
                drainScheduled = false;
                queued.clear();
                writes = pending.toArray(PendingWrite[]::new);
            }
            for (PendingWrite write : writes) {
                write.promise.tryFailure(failure);
                // A stopped loop can also reject promise notification tasks.
                completeWrite(write);
            }
            channel.close();
        }
    }

    /**
     * Drains FIFO writes with count and source-byte flush thresholds, pausing at water marks.
     */
    private void drainWrites() {
        try {
            installOutboundBudget(channel.pipeline(), limits, budget);
            if (channel.pipeline().context(recoveryHandler) == null) {
                channel.pipeline().addLast(recoveryHandler);
            }
            armWriteDeadline();
            long batchBytes = 0;
            boolean needsFlush = false;
            for (int i = 0; i < 64; i++) {
                PendingWrite write;
                synchronized (this) {
                    write = queued.peekFirst();
                }
                if (write == null || closed || !channel.isActive()) {
                    break;
                }
                if (!channel.isWritable() || (needsFlush && write.bytes > BATCH_BYTES - batchBytes)) {
                    channel.flush();
                    needsFlush = false;
                    batchBytes = 0;
                }
                if (!channel.isWritable()) {
                    break;
                }
                synchronized (this) {
                    queued.pollFirst();
                }
                writeOnEventLoop(write);
                needsFlush = true;
                batchBytes += Math.min(write.bytes, BATCH_BYTES);
                if (batchBytes >= BATCH_BYTES || !channel.isWritable()) {
                    channel.flush();
                    needsFlush = false;
                    batchBytes = 0;
                }
            }
            if (needsFlush) {
                channel.flush();
            }
        } catch (Throwable failure) {
            closed = true;
            failPendingWrites(failure);
            channel.close();
        }
        synchronized (this) {
            drainScheduled = false;
            resumeWrites();
        }
    }

    /**
     * Coalesces recovery into one FIFO drain without polling an unwritable transport.
     */
    private synchronized void resumeWrites() {
        if (!closed && channel.isActive() && channel.isWritable() && !queued.isEmpty() && !drainScheduled) {
            drainScheduled = true;
            scheduleDrain();
        }
    }

    /**
     * Maintains one timer for the oldest admitted write, including already forwarded writes.
     */
    private synchronized void armWriteDeadline() {
        PendingWrite oldest = pending.isEmpty() ? null : pending.iterator().next();
        if (oldest == deadlineWrite) {
            return;
        }
        if (writeDeadline != null) {
            writeDeadline.cancel(false);
            writeDeadline = null;
        }
        deadlineWrite = oldest;
        if (oldest != null && !closed) {
            PendingWrite target = oldest;
            long delay = Math.max(0, writeTimeoutNanos - (System.nanoTime() - target.admittedNanos));
            writeDeadline = channel.eventLoop().schedule(() -> expireWrite(target), delay, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Closes a stalled transport without releasing any source ahead of transport ownership.
     *
     * @param target the write owning this deadline
     */
    private void expireWrite(PendingWrite target) {
        synchronized (this) {
            if (closed || !pending.contains(target) || deadlineWrite != target) {
                return;
            }
            closed = true;
        }
        FlowControlMetrics.WRITE_TIMEOUTS.increment();
        failPendingWrites(new TimeoutException("Socket write completion deadline exceeded"));
        channel.close();
    }

    /**
     * Performs a previously admitted write without bypassing older event-loop tasks.
     *
     * @param write the retained application write
     */
    private void writeOnEventLoop(PendingWrite write) {
        if (write.promise.isDone()) {
            return;
        }
        if (closed || !channel.isActive()) {
            write.promise.tryFailure(new RejectedExecutionException("Socket stopped accepting ordered writes"));
            closed = true;
            channel.close();
            return;
        }
        try {
            installOutboundBudget(channel.pipeline(), limits, budget);
            write.forwarded = true;
            channel.write(write.message, write.promise);
        } catch (Throwable failure) {
            write.promise.tryFailure(failure);
            channel.close();
        }
    }

    /**
     * Releases one admitted write after transport completion or pre-write rejection.
     *
     * @param write the completed application write
     */
    private void completeWrite(PendingWrite write) {
        synchronized (this) {
            if (!pending.remove(write)) {
                return;
            }
            pendingPayloadBytes -= write.bytes;
        }
        FlowControlMetrics.PENDING_WRITES.decrement();
        try {
            EventResources.releasePayload(write.payload);
            if (!write.forwarded) {
                releaseRawMessage(write.message);
            }
        } finally {
            budget.release(write.bytes);
            FlowControlMetrics.PENDING_PAYLOAD_BYTES.add(-write.bytes);
        }
        if (!write.promise.isSuccess()) {
            closed = true;
            channel.close();
        } else if (!closed && channel.eventLoop().inEventLoop()) {
            try {
                armWriteDeadline();
            } catch (Throwable failure) {
                closed = true;
                failPendingWrites(failure);
                channel.close();
            }
        }
    }

    /**
     * Resolves queued writes when transport closure precedes their event-loop task.
     */
    private void failPendingWrites() {
        failPendingWrites(new ClosedChannelException());
    }

    /**
     * Fails all remaining admission ownership and cancels its deadline on terminal closure.
     *
     * @param failure the terminal write cause
     */
    private void failPendingWrites(Throwable failure) {
        PendingWrite[] writes;
        synchronized (this) {
            closed = true;
            writes = pending.toArray(PendingWrite[]::new);
            queued.clear();
            if (writeDeadline != null) {
                writeDeadline.cancel(false);
                writeDeadline = null;
            }
            deadlineWrite = null;
        }
        for (PendingWrite write : writes) {
            write.promise.tryFailure(failure);
            completeWrite(write);
        }
    }

    /**
     * Releases ownership-transferred raw messages without releasing an Event's caller-owned source.
     *
     * @param message the rejected or cancelled application message
     */
    private static void releaseRawMessage(Object message) {
        if (!(message instanceof Event)) {
            ReferenceCountUtil.release(message);
        }
    }

    /**
     * Returns admitted writes awaiting their channel completion.
     *
     * @return the bounded pending application-write count
     */
    public synchronized int pendingWrites() {
        return pending.size();
    }

    /**
     * Returns source estimates still owned by admitted application writes.
     *
     * @return estimated payload bytes pending completion
     */
    public synchronized long pendingPayloadBytes() {
        return pendingPayloadBytes;
    }

    /**
     * Installs transport accounting before codecs, including login and direct HTTP writes.
     *
     * @param pipeline the channel pipeline, accessed on its event loop
     * @param limits   the per-channel encoded-byte allowance
     * @param budget   the context-wide reservation owner
     */
    public static void installOutboundBudget(ChannelPipeline pipeline, OutboundLimits limits, OutboundBudget budget) {
        if (pipeline.get(OutboundBufferLimiter.class) == null) {
            pipeline.addFirst("zeaOutboundBudget", new OutboundBufferLimiter(limits, budget));
        }
    }

    /**
     * Returns the associated Netty channel.
     *
     * @return the associated Netty channel
     */
    public Channel getChannel() {
        return channel;
    }

    /**
     * Writes a disconnect opcode or event and closes the channel when that write completes.
     */
    @Override
    public void close() {
        LOG.debug("Going to close tcp connection in class: {}", this.getClass().getName());
        closed = true;
        if (!channel.isOpen() || !channel.isWritable() || pendingWrites() != 0) {
            channel.close();
            return;
        }

        Event event = Events.event(null, Events.DISCONNECT);
        if (channel.isActive() && supportsEventOutbound(channel.pipeline())) {
            channel.writeAndFlush(event).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        // Login pipelines without an event encoder use a one-byte disconnect opcode.
        channel.writeAndFlush(NettyUtils.createBufferForOpcode(event.getType()))
                .addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        String channelId = "Socket channel: ";
        if (channel != null) {
            channelId += channel.toString();
        } else {
            channelId += "0";
        }
        String sender = "Netty " + channelId;
        return sender;
    }

    /**
     * Tests whether the pipeline contains one of the supported event encoders.
     *
     * @param pipeline the Netty handler pipeline
     * @return {@code true} if an event encoder is present
     */
    private boolean supportsEventOutbound(ChannelPipeline pipeline) {
        if (pipeline == null) {
            return false;
        }
        return pipeline.get(JsonEncoder.class) != null
                || pipeline.get(WebSocketEventEncoder.class) != null
                || pipeline.get(MessageBufferEventEncoder.class) != null
                || pipeline.get(SbeEventEncoder.class) != null
                || pipeline.get(EventEncoder.class) != null
                || hasGenericEventEncoder(pipeline);
    }

    /**
     * Scans pipeline message encoders for a supported event-encoder implementation.
     *
     * @param pipeline the Netty handler pipeline
     * @return {@code true} if a supported message encoder is found
     */
    private boolean hasGenericEventEncoder(ChannelPipeline pipeline) {
        for (String name : pipeline.names()) {
            if (pipeline.get(name) instanceof MessageToMessageEncoder<?> encoder) {
                if (encoder instanceof JsonEncoder
                        || encoder instanceof WebSocketEventEncoder
                        || encoder instanceof MessageBufferEventEncoder
                        || encoder instanceof SbeEventEncoder
                        || encoder instanceof EventEncoder) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Captures an application write and its independently retained Event source.
     */
    private static final class PendingWrite {

        /**
         * Original message retained until its event-loop task has run.
         */
        private final Object message;

        /**
         * Captured native Event source, or null for ownership-transferred raw writes.
         */
        private final Object payload;

        /**
         * Uncancellable completion preventing premature native payload release.
         */
        private final ChannelPromise promise;

        /**
         * Captured source estimate reserved until this write completes.
         */
        private final long bytes;

        /**
         * Monotonic admission timestamp used by the shared per-sender deadline.
         */
        private final long admittedNanos = System.nanoTime();

        /**
         * Whether ownership has been transferred to the outbound pipeline.
         */
        private volatile boolean forwarded;

        /**
         * Captures one admitted write.
         *
         * @param message the application message
         * @param payload the independently retained Event source
         * @param promise the transport completion
         * @param bytes   the reserved source estimate
         */
        private PendingWrite(Object message, Object payload, ChannelPromise promise, long bytes) {
            this.message = message;
            this.payload = payload;
            this.promise = promise;
            this.bytes = bytes;
        }
    }
}
