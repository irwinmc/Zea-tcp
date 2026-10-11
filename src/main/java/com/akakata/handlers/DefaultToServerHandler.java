package com.akakata.handlers;

import com.akakata.app.PlayerSession;
import com.akakata.event.Event;
import com.akakata.event.Events;
import com.akakata.event.EventResources;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Submits decoded events to a player session and releases the inbound payload reference.
 *
 * <p>The dispatcher retains accepted payloads before this handler releases the
 * inbound owner. Channel inactivity and pipeline failures close the player session.
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
     * Creates a default to server handler instance with the supplied dependencies.
     *
     * @param playerSession the player session
     */
    public DefaultToServerHandler(PlayerSession playerSession) {
        super();
        this.playerSession = playerSession;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void channelRead0(ChannelHandlerContext ctx, Event e) throws Exception {
        Object payloadReference = EventResources.payloadReference(e);
        try {
            playerSession.onEvent(e);
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
        playerSession.onEvent(event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        LOG.debug("Channel {} is closed.", ctx.channel());
        if (!playerSession.isShuttingDown()) {
            // Should not send close to session, since reconnection/other
            // business logic might be in place.
            Event event = Events.event(null, Events.DISCONNECT);
            playerSession.onEvent(event);
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
            playerSession.onEvent(event);
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
