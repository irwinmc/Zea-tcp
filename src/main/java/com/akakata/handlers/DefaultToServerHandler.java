package com.akakata.handlers;

import com.akakata.app.PlayerSession;
import com.akakata.event.Event;
import com.akakata.event.Events;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Kelvin
 */
public class DefaultToServerHandler extends SimpleChannelInboundHandler<Event> {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultToServerHandler.class);

    /**
     * The player session associated with this stateful business handler.
     */
    private final PlayerSession playerSession;

    public DefaultToServerHandler(PlayerSession playerSession) {
        super();
        this.playerSession = playerSession;
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Event e) throws Exception {
        playerSession.onEvent(e);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOG.warn("Exception during network communication: ", cause);

        // Exception event
        Event event = Events.event(cause, Events.EXCEPTION);
        playerSession.onEvent(event);
    }

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

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            LOG.warn("Channel {} has been idle, exception event will be raised now: ", ctx.channel());

            // TODO check if setting payload as non-throwable cause issue?
            Event event = Events.event(evt, Events.EXCEPTION);
            playerSession.onEvent(event);
        }
    }

    public PlayerSession getPlayerSession() {
        return playerSession;
    }
}
