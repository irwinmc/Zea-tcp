package com.akakata.handlers;

import com.akakata.app.PlayerSession;
import com.akakata.event.Event;
import com.akakata.event.Events;
import com.akakata.event.EventResources;
import com.akakata.event.impl.NettyEventDispatcher;
import com.akakata.event.impl.ShardedEventDispatcher;
import com.akakata.event.EventDispatcher;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Submits decoded events to a player session and releases the inbound payload reference.
 *
 * <p>The dispatcher retains accepted payloads before this handler releases the
 * inbound owner. Channel inactivity and pipeline failures close the player session.
 * High business-queue pressure or an unwritable socket pauses reads until both
 * queues and the socket recover. A full queue rejects input and closes the connection;
 * a pause lasting five seconds also closes it. Accepted events retain FIFO ordering.
 *
 * @author Kelvin
 */
public class DefaultToServerHandler extends SimpleChannelInboundHandler<Event> {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(DefaultToServerHandler.class);

    /**
     * The player session associated with this stateful business handler.
     */
    private final PlayerSession playerSession;

    /**
     * Maximum sustained read pause before the connection is closed.
     */
    private final long maximumPauseMillis;

    /**
     * Whether this handler changed an enabled auto-read flag to disabled.
     */
    private boolean paused;

    /**
     * Scheduled queue recovery probe, confined to the channel event loop.
     */
    private ScheduledFuture<?> recoveryProbe;

    /**
     * Scheduled deadline for a sustained pause, confined to the channel event loop.
     */
    private ScheduledFuture<?> pauseDeadline;

    /**
     * Creates a default to server handler instance with the supplied dependencies.
     *
     * @param playerSession the player session
     */
    public DefaultToServerHandler(PlayerSession playerSession) {
        this(playerSession, 5_000);
    }

    /**
     * Creates an inbound bridge with a configurable sustained-pressure deadline.
     *
     * @param playerSession the session associated with the channel
     * @param maximumPauseMillis the positive maximum read-pause duration
     * @throws IllegalArgumentException if the pause deadline is not positive
     */
    public DefaultToServerHandler(PlayerSession playerSession, long maximumPauseMillis) {
        super();
        if (maximumPauseMillis <= 0) {
            throw new IllegalArgumentException("Maximum read pause must be positive");
        }
        this.playerSession = playerSession;
        this.maximumPauseMillis = maximumPauseMillis;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void channelRead0(ChannelHandlerContext ctx, Event e) throws Exception {
        Object payloadReference = EventResources.payloadReference(e);
        try {
            if (!playerSession.onEvent(e)) {
                terminate(ctx);
            } else {
                updateReadState(ctx);
            }
        } finally {
            EventResources.releasePayload(payloadReference);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOG.warn("Exception during network communication: ", cause);

        // Exception event
        Event event = Events.event(cause, Events.EXCEPTION);
        if (!playerSession.onEvent(event)) {
            terminate(ctx);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        cancelPause();
        LOG.debug("Channel {} is closed.", ctx.channel());
        if (!playerSession.isShuttingDown()) {
            // Should not send close to session, since reconnection/other
            // business logic might be in place.
            Event event = Events.event(null, Events.DISCONNECT);
            if (!playerSession.onEvent(event)) {
                playerSession.close();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            LOG.warn("Channel {} has been idle, exception event will be raised now: ", ctx.channel());

            // TODO check if setting payload as non-throwable cause issue?
            Event event = Events.event(evt, Events.EXCEPTION);
            if (!playerSession.onEvent(event)) {
                terminate(ctx);
            }
        } else {
            ctx.fireUserEventTriggered(evt);
        }
    }

    /**
     * Rechecks socket pressure without resuming reads above the business low water mark.
     *
     * @param ctx the channel handler context
     */
    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        updateReadState(ctx);
        ctx.fireChannelWritabilityChanged();
    }

    /**
     * Cancels recovery tasks and restores reads only when this handler paused them.
     *
     * @param ctx the removed handler context
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        boolean restore = paused;
        cancelPause();
        if (restore && ctx.channel().isActive()) {
            ctx.channel().config().setAutoRead(true);
        }
    }

    /**
     * Applies high/low queue hysteresis and schedules bounded read recovery.
     *
     * @param ctx the channel handler context
     */
    private void updateReadState(ChannelHandlerContext ctx) {
        if (!ctx.channel().isActive()) {
            cancelPause();
            return;
        }
        if (paused) {
            if (ctx.channel().isWritable() && belowWaterMark(false)) {
                cancelPause();
                ctx.channel().config().setAutoRead(true);
            }
        } else if (ctx.channel().config().isAutoRead()
                && (!ctx.channel().isWritable() || !belowWaterMark(true))) {
            paused = true;
            ctx.channel().config().setAutoRead(false);
            pauseDeadline = ctx.executor().schedule(() -> terminate(ctx), maximumPauseMillis, TimeUnit.MILLISECONDS);
        }
        if (paused && (recoveryProbe == null || recoveryProbe.isDone())) {
            recoveryProbe = ctx.executor().schedule(() -> {
                recoveryProbe = null;
                updateReadState(ctx);
            }, 10, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Checks the assigned session queue and the application's separate Game queue.
     *
     * @param high whether to use the 75 percent high mark instead of the 50 percent low mark
     * @return whether both queues are below their selected threshold
     */
    private boolean belowWaterMark(boolean high) {
        EventDispatcher dispatcher = playerSession.getEventDispatcher();
        if (dispatcher instanceof ShardedEventDispatcher group) {
            dispatcher = group.getShardForSession(playerSession);
        }
        return belowWaterMark(dispatcher, high)
                && (playerSession.getGame() == null
                || belowWaterMark(playerSession.getGame().getEventDispatcher(), high));
    }

    /**
     * Samples one bounded Netty business queue without changing its execution order.
     *
     * @param dispatcher the queue owner, possibly a custom implementation
     * @param high whether to use the high threshold
     * @return whether the queue is below the selected threshold
     */
    private static boolean belowWaterMark(EventDispatcher dispatcher, boolean high) {
        if (dispatcher instanceof NettyEventDispatcher netty) {
            int capacity = netty.getQueueCapacity();
            int threshold = high ? capacity - capacity / 4 : capacity / 2;
            return netty.getQueueSize() < threshold;
        }
        return true;
    }

    /**
     * Cancels tasks owned by a read pause and clears the ownership marker.
     */
    private void cancelPause() {
        paused = false;
        if (recoveryProbe != null) {
            recoveryProbe.cancel(false);
            recoveryProbe = null;
        }
        if (pauseDeadline != null) {
            pauseDeadline.cancel(false);
            pauseDeadline = null;
        }
    }

    /**
     * Closes an overloaded transport and synchronously releases its session resources.
     *
     * @param ctx the channel handler context
     */
    private void terminate(ChannelHandlerContext ctx) {
        cancelPause();
        try {
            playerSession.close();
        } finally {
            ctx.close();
        }
    }

    /**
     * Returns the player session.
     *
     * @return the player session
     */
    public PlayerSession getPlayerSession() {
        return playerSession;
    }
}
