package com.akakata.communication.impl;

import com.akakata.communication.OutboundBudget;
import com.akakata.communication.OutboundLimits;
import com.akakata.handlers.ConnectionScopedHandler;
import com.akakata.metrics.FlowControlMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

import java.util.concurrent.RejectedExecutionException;

/**
 * Limits encoded bytes on the transport side of all application encoders.
 * All accounting runs on the channel event loop. No secondary message queue is created.
 */
final class OutboundBufferLimiter extends ChannelOutboundHandlerAdapter implements ConnectionScopedHandler {

    /**
     * Maximum bytes held by writes whose promises have not completed.
     */
    private final int maximumPendingBytes;

    /**
     * Aggregate source and encoded-byte reservation owner.
     */
    private final OutboundBudget budget;

    /**
     * Encoded bytes awaiting transport completion, confined to the channel event loop.
     */
    private long pendingBytes;

    /**
     * Creates a limiter for a single socket pipeline.
     *
     * @param limits the per-connection outbound budgets
     */
    OutboundBufferLimiter(OutboundLimits limits) {
        this(limits, OutboundBudget.DEFAULT);
    }

    /**
     * Creates a transport limiter using a shared context allowance.
     *
     * @param limits the per-connection outbound budgets
     * @param budget the aggregate reservation owner
     */
    OutboundBufferLimiter(OutboundLimits limits, OutboundBudget budget) {
        maximumPendingBytes = limits.maximumPendingBytes();
        this.budget = java.util.Objects.requireNonNull(budget);
    }

    /**
     * Rejects an encoded write that exceeds a hard per-channel or aggregate byte budget.
     *
     * @param ctx     the transport-side handler context
     * @param message the encoded buffer or frame transferred to this handler
     * @param promise the write completion
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        promise = promise.unvoid();
        long bytes = message instanceof ByteBuf buffer ? buffer.readableBytes()
                : message instanceof ByteBufHolder holder ? holder.content().readableBytes()
                : Math.max(0, ctx.channel().config().getMessageSizeEstimator().newHandle().size(message));
        if (bytes > maximumPendingBytes - pendingBytes || !budget.reserve(bytes)) {
            FlowControlMetrics.WRITE_REJECTIONS.increment();
            ReferenceCountUtil.release(message);
            promise.tryFailure(new RejectedExecutionException("Socket encoded-byte budget exceeded"));
            ctx.close();
            return;
        }
        pendingBytes += bytes;
        FlowControlMetrics.PENDING_SOCKET_BYTES.add(bytes);
        promise.addListener(completed -> {
            pendingBytes -= bytes;
            budget.release(bytes);
            FlowControlMetrics.PENDING_SOCKET_BYTES.add(-bytes);
        });
        ctx.write(message, promise);
    }
}
