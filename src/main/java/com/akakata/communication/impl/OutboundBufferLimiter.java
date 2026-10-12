package com.akakata.communication.impl;

import com.akakata.communication.OutboundLimits;
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
final class OutboundBufferLimiter extends ChannelOutboundHandlerAdapter {

    /**
     * Maximum bytes held by writes whose promises have not completed.
     */
    private final int maximumPendingBytes;

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
        maximumPendingBytes = limits.maximumPendingBytes();
    }

    /**
     * Rejects an encoded write that exceeds its byte budget or channel water mark.
     *
     * @param ctx the transport-side handler context
     * @param message the encoded buffer or frame transferred to this handler
     * @param promise the write completion
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        promise = promise.unvoid();
        long bytes = message instanceof ByteBuf buffer ? buffer.readableBytes()
                : message instanceof ByteBufHolder holder ? holder.content().readableBytes()
                : Math.max(0, ctx.channel().config().getMessageSizeEstimator().newHandle().size(message));
        if (!ctx.channel().isWritable() || bytes > maximumPendingBytes - pendingBytes) {
            FlowControlMetrics.WRITE_REJECTIONS.increment();
            ReferenceCountUtil.release(message);
            promise.tryFailure(new RejectedExecutionException("Socket encoded-byte budget or water mark exceeded"));
            ctx.close();
            return;
        }
        pendingBytes += bytes;
        FlowControlMetrics.PENDING_SOCKET_BYTES.add(bytes);
        promise.addListener(completed -> {
            pendingBytes -= bytes;
            FlowControlMetrics.PENDING_SOCKET_BYTES.add(-bytes);
        });
        ctx.write(message, promise);
    }
}
