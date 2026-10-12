package com.akakata.handlers;

import com.akakata.metrics.FlowControlMetrics;
import com.akakata.server.ConnectionAdmission;
import com.akakata.server.TransportLimits;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns socket admission, the login deadline and read-idle expiry on the channel loop.
 * This handler and its idle detector survive the TCP login pipeline transition.
 */
public final class TransportLifecycleHandler extends ChannelInboundHandlerAdapter {

    /**
     * Shared socket admission budget.
     */
    private final ConnectionAdmission admission;

    /**
     * Scheduled login deadline, cancelled on authentication or channel cleanup.
     */
    private ScheduledFuture<?> deadline;

    /**
     * Creates one channel's lifetime guard.
     *
     * @param admission the shared per-context connection owner
     */
    public TransportLifecycleHandler(ConnectionAdmission admission) {
        this.admission = admission;
    }

    /**
     * Installs persistent admission and idle handling before protocol codecs.
     *
     * @param pipeline  the new socket pipeline
     * @param admission the shared TCP/WebSocket policy owner
     */
    public static void install(ChannelPipeline pipeline, ConnectionAdmission admission) {
        if (admission.limits().readIdleMillis() > 0)
            pipeline.addLast("zeaReadIdle", new IdleStateHandler(admission.limits().readIdleMillis(), 0, 0, TimeUnit.MILLISECONDS));
        pipeline.addLast("zeaLifecycle", new TransportLifecycleHandler(admission));
    }

    /**
     * Cancels the login deadline after the authenticated pipeline is fully installed.
     *
     * @param channel the authenticated transport
     */
    public static void authenticated(Channel channel) {
        TransportLifecycleHandler handler = channel.pipeline().get(TransportLifecycleHandler.class);
        if (handler != null) handler.cancelDeadline();
    }

    /**
     * Returns the configured application frame limit for an installed pipeline.
     *
     * @param pipeline the socket pipeline
     * @return application frame bytes excluding TCP or WebSocket transport headers
     */
    public static int maximumFrameBytes(ChannelPipeline pipeline) {
        TransportLifecycleHandler handler = pipeline.get(TransportLifecycleHandler.class);
        return handler == null ? TransportLimits.DEFAULT.maximumFrameBytes() : handler.admission.limits().maximumFrameBytes();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        if (!admission.admit(ctx.channel())) {
            ctx.close();
            return;
        }
        deadline = ctx.executor().schedule(() -> {
            FlowControlMetrics.LOGIN_TIMEOUTS.increment();
            ctx.close();
        }, admission.limits().loginTimeoutMillis(), TimeUnit.MILLISECONDS);
        ctx.fireChannelActive();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event instanceof IdleStateEvent) {
            FlowControlMetrics.IDLE_TIMEOUTS.increment();
            ctx.close();
        } else ctx.fireUserEventTriggered(event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cancelDeadline();
        ctx.fireChannelInactive();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        cancelDeadline();
    }

    /**
     * Cancels owned timeout state idempotently on the event loop.
     */
    private void cancelDeadline() {
        if (deadline != null) {
            deadline.cancel(false);
            deadline = null;
        }
    }
}
