package com.akakata.handlers;

import com.akakata.metrics.FlowControlMetrics;
import com.akakata.server.HttpAdmission;
import com.akakata.server.HttpLimits;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCountUtil;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Accounts HTTP sockets in the shared connection allowance and bounds slow requests.
 *
 * <p>Install after the HTTP codec and before aggregation. The absolute deadline
 * starts on activation and restarts after each complete body, including keep-alive
 * requests. Receiving partial bytes never extends it. HTTP has no login deadline.
 */
public final class HttpLifecycleHandler extends ChannelInboundHandlerAdapter {

    /**
     * Connection allowance shared with TCP and WebSocket listeners.
     */
    private final HttpAdmission admission;

    /**
     * Validated HTTP deadlines and body policy.
     */
    private final HttpLimits limits;

    /**
     * Current absolute request deadline, confined to the channel event loop.
     */
    private ScheduledFuture<?> deadline;

    /**
     * Prevents repeated activation from acquiring extra slots or extending a deadline.
     */
    private boolean activated;

    /**
     * Creates an HTTP lifetime guard for one connection.
     *
     * @param admission the HTTP owner within the shared connection allowance
     */
    public HttpLifecycleHandler(HttpAdmission admission) {
        this.admission = java.util.Objects.requireNonNull(admission);
        this.limits = admission.limits();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        if (!activated) {
            activated = true;
            if (!admission.admit(ctx.channel())) {
                ctx.close();
                return;
            }
            armDeadline(ctx);
        }
        ctx.fireChannelActive();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (!ctx.channel().isActive()) {
            ReferenceCountUtil.release(message);
            return;
        }
        if (message instanceof LastHttpContent) {
            armDeadline(ctx);
        }
        ctx.fireChannelRead(message);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if (event instanceof IdleStateEvent) {
            FlowControlMetrics.IDLE_TIMEOUTS.increment();
            ctx.close();
        } else {
            ctx.fireUserEventTriggered(event);
        }
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
     * Schedules the next absolute deadline without allowing partial bytes to reset it.
     *
     * @param ctx the channel-owned handler context
     */
    private void armDeadline(ChannelHandlerContext ctx) {
        cancelDeadline();
        deadline = ctx.executor().schedule(() -> {
            FlowControlMetrics.HTTP_TIMEOUTS.increment();
            ctx.close();
        }, limits.requestTimeoutMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Cancels the owned timer idempotently.
     */
    private void cancelDeadline() {
        if (deadline != null) {
            deadline.cancel(false);
            deadline = null;
        }
    }
}
