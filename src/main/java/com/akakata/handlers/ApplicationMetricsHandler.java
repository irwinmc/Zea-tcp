package com.akakata.handlers;

import com.akakata.event.Event;
import com.akakata.event.Events;
import com.akakata.metrics.ServerMetrics;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

import java.util.Objects;

/**
 * Counts decoded inbound application messages after framing and before Session admission.
 *
 * <p>Malformed input contributes an error rather than a message. Login and control
 * opcodes are excluded. Successful outbound messages are counted by the socket sender.
 *
 * @since 0.8.4
 */
public final class ApplicationMetricsHandler extends ChannelInboundHandlerAdapter {

    /**
     * Borrowed counter collector.
     */
    private final ServerMetrics metrics;

    /**
     * Creates an adapter for one application pipeline.
     *
     * @param metrics counter owner
     */
    public ApplicationMetricsHandler(ServerMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Counts decoded business inputs without retaining or modifying them.
     *
     * @param ctx     channel context
     * @param message decoded event forwarded unchanged
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (message instanceof Event event && event.getType() == Events.SESSION_MESSAGE) {
            metrics.recordMessageReceived();
        }
        ctx.fireChannelRead(message);
    }

    /**
     * Counts decoder failures before forwarding the normal cleanup path.
     *
     * @param ctx   channel context
     * @param cause decode or upstream pipeline failure
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        metrics.recordError();
        ctx.fireExceptionCaught(cause);
    }
}
