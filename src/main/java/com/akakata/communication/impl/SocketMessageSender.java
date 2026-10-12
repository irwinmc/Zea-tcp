package com.akakata.communication.impl;

import com.akakata.communication.MessageSender;
import com.akakata.communication.OutboundLimits;
import com.akakata.event.Event;
import com.akakata.event.EventResources;
import com.akakata.event.Events;
import com.akakata.handlers.codec.EventEncoder;
import com.akakata.handlers.codec.JsonEncoder;
import com.akakata.handlers.codec.MessageBufferEventEncoder;
import com.akakata.handlers.codec.SbeEventEncoder;
import com.akakata.handlers.codec.WebSocketEventEncoder;
import com.akakata.metrics.FlowControlMetrics;
import com.akakata.util.NettyUtils;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.ClosedChannelException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;

/**
 * Sends ordered socket writes with bounded admission and encoded-buffer accounting.
 *
 * <p>Event writes retain the native payload until the write future completes.
 * Writes always enter the channel event-loop queue in admission order. A full write
 * budget, encoded-byte budget or nonwritable channel fails the write and closes the
 * connection. This prevents later updates from following a rejected state update.
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
     * Associated transport channel.
     */
    private final Channel channel;

    /**
     * Per-connection application-write and encoded-buffer limits.
     */
    private final OutboundLimits limits;

    /**
     * Admitted writes, including queued event-loop tasks; guarded by this sender.
     */
    private final Set<PendingWrite> pending = new HashSet<>();

    /**
     * FIFO admission order consumed in bounded batches by the channel event loop.
     */
    private final java.util.ArrayDeque<PendingWrite> queued = new java.util.ArrayDeque<>();

    /**
     * Whether a batch drain has been scheduled or is executing; guarded by this sender.
     */
    private boolean drainScheduled;

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
     * @param limits the positive application-write and encoded-byte limits
     */
    public SocketMessageSender(Channel channel, OutboundLimits limits) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.limits = Objects.requireNonNull(limits, "limits");
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
        if (pending.size() >= limits.maximumPendingWrites() || !channel.isWritable()) {
            FlowControlMetrics.WRITE_REJECTIONS.increment();
            closed = true;
            releaseRawMessage(message);
            channel.close();
            return channel.newFailedFuture(new RejectedExecutionException("Socket write budget or water mark exceeded"));
        }
        Object payload = message instanceof Event event ? EventResources.retainPayload(event) : null;
        ChannelPromise promise = channel.newPromise();
        promise.setUncancellable();
        PendingWrite write = new PendingWrite(message, payload, promise);
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
        try { channel.eventLoop().execute(this::drainWrites); }
        catch (Throwable failure) {
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
     * Writes at most 64 admitted messages in FIFO order and flushes once per batch.
     */
    private void drainWrites() {
        for (int i = 0; i < 64; i++) {
            PendingWrite write;
            synchronized (this) { write = queued.pollFirst(); }
            if (write == null) break;
            writeOnEventLoop(write);
        }
        channel.flush();
        synchronized (this) {
            if (queued.isEmpty()) drainScheduled = false;
            else scheduleDrain();
        }
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
        if (closed || !channel.isActive() || !channel.isWritable()) {
            write.promise.tryFailure(new RejectedExecutionException("Socket stopped accepting ordered writes"));
            closed = true;
            channel.close();
            return;
        }
        try {
            if (channel.pipeline().get(OutboundBufferLimiter.class) == null) {
                channel.pipeline().addFirst("zeaOutboundBudget", new OutboundBufferLimiter(limits));
            }
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
        }
        FlowControlMetrics.PENDING_WRITES.decrement();
        EventResources.releasePayload(write.payload);
        if (!write.forwarded) {
            releaseRawMessage(write.message);
        }
        if (!write.promise.isSuccess()) {
            closed = true;
            channel.close();
        }
    }

    /**
     * Resolves queued writes when transport closure precedes their event-loop task.
     */
    private void failPendingWrites() {
        PendingWrite[] writes;
        synchronized (this) {
            closed = true;
            writes = pending.toArray(PendingWrite[]::new);
            queued.clear();
        }
        for (PendingWrite write : writes) {
            write.promise.tryFailure(new ClosedChannelException());
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
         * Whether ownership has been transferred to the outbound pipeline.
         */
        private volatile boolean forwarded;

        /**
         * Captures one admitted write.
         *
         * @param message the application message
         * @param payload the independently retained Event source
         * @param promise the transport completion
         */
        private PendingWrite(Object message, Object payload, ChannelPromise promise) {
            this.message = message;
            this.payload = payload;
            this.promise = promise;
        }
    }
}
