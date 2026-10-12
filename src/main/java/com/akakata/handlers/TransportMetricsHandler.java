package com.akakata.handlers;

import com.akakata.metrics.ServerMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.FileRegion;

import java.util.Objects;

/**
 * Records observed socket lifetimes and successful encoded transport bytes.
 *
 * <p>Install before transport codecs. Counts include framing and handshake traffic;
 * connection admission remains a separate policy. Promise completion records writes
 * once, including zero-copy file transfers and void-promise callers.
 *
 * @since 0.8.4
 */
public final class TransportMetricsHandler extends ChannelDuplexHandler implements ConnectionScopedHandler {

    /**
     * Counter collector borrowed from the application.
     */
    private final ServerMetrics metrics;

    /**
     * Prevents repeated activation events from counting the same connection twice.
     */
    private boolean activated;

    /**
     * Creates a per-connection collector adapter.
     *
     * @param metrics counter owner
     */
    public TransportMetricsHandler(ServerMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Records an observed socket once and pairs it with actual channel closure.
     *
     * @param ctx channel context
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        if (!activated) {
            activated = true;
            metrics.recordConnection();
            ctx.channel().closeFuture().addListener(ignored -> metrics.recordDisconnection());
        }
        ctx.fireChannelActive();
    }

    /**
     * Counts raw bytes before codecs consume their reader indexes.
     *
     * @param ctx     channel context
     * @param message borrowed inbound data forwarded unchanged
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        metrics.recordBytesReceived(bytes(message));
        ctx.fireChannelRead(message);
    }

    /**
     * Counts encoded bytes only after successful transport completion.
     *
     * @param ctx     channel context
     * @param message outbound ownership transferred unchanged
     * @param promise caller's completion promise
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
        long count = bytes(message);
        ChannelPromise completion = promise.unvoid();
        completion.addListener(future -> {
            if (future.isSuccess()) {
                metrics.recordBytesSent(count);
            } else {
                metrics.recordError();
            }
        });
        ctx.write(message, completion);
    }

    /**
     * Captures byte length while native ownership is still valid.
     *
     * @param message buffer, frame or file region
     * @return encoded byte length, or zero for control objects
     */
    private static long bytes(Object message) {
        if (message instanceof ByteBuf buffer) {
            return buffer.readableBytes();
        }
        if (message instanceof ByteBufHolder holder) {
            return holder.content().readableBytes();
        }
        if (message instanceof FileRegion region) {
            return region.count();
        }
        return 0;
    }
}
