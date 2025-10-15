package com.akakata.handlers;

import com.akakata.app.Game;
import com.akakata.app.PlayerSession;
import com.akakata.app.Session;
import com.akakata.communication.impl.SocketMessageSender;
import com.akakata.event.Event;
import com.akakata.event.Events;
import com.akakata.protocols.Protocol;
import com.akakata.security.Credentials;
import com.akakata.security.crypto.AesGcmCipher;
import com.akakata.server.impl.AbstractNettyServer;
import com.akakata.service.SessionManagerService;
import com.akakata.util.NettyUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.ChannelHandler.Sharable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author Kelvin
 */
@Sharable
public class LoginHandler extends SimpleChannelInboundHandler<Event> {

    private static final Logger LOG = LoggerFactory.getLogger(LoginHandler.class);
    /**
     * Used for book keeping purpose. It will count all open channels.
     * Currently closed channels will not lead to a decrement.
     */
    private static final AtomicInteger CHANNEL_COUNTER = new AtomicInteger(0);
    /**
     * Protocol
     */
    protected Protocol protocol;
    /**
     * Game instance
     */
    protected Game game;
    /**
     * Session manage service
     */
    protected SessionManagerService<Credentials> sessionManagerService;

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Event event) throws Exception {
        final Channel channel = ctx.channel();
        final ByteBuf object = (ByteBuf) event.getSource();

        try {
            // Check event type
            int type = event.getType();
            if (type == Events.LOG_IN) {
                LOG.debug("Login from remote address {}.", channel.remoteAddress());

                // Play handle login
                Credentials credentials = sessionManagerService.verify(object);
                handleLogin(channel, credentials);
            } else {
                LOG.error("Invalid event {} sent from remote address {}. Going to close channel {}.",
                        new Object[]{event.getType(), channel.remoteAddress(), channel});

                closeChannelWithLoginFailure(channel);
            }
        } finally {
            object.release();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        AbstractNettyServer.ALL_CHANNELS.add(ctx.channel());
        LOG.debug("Added Channel with id: {} as the {}th open channel.", ctx.channel(), CHANNEL_COUNTER.incrementAndGet());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOG.error("Exception during network communication: {}.", ctx.channel(), cause);
        ctx.channel().close();
    }

    protected void handleLogin(Channel channel, Credentials credentials) {
        // Check credentials
        if (credentials != null) {
            handleSession(credentials);
            handleJoinGame(channel, credentials);
        } else {
            closeChannelWithLoginFailure(channel);
        }
    }

    protected void handleSession(Credentials credentials) {
        // Find player session
        PlayerSession playerSession = (PlayerSession) sessionManagerService.getSession(credentials);
        if (playerSession != null) {
            synchronized (playerSession) {
                // If player session connected, must log out old player session
                if (playerSession.getStatus() == Session.Status.CONNECTED) {
                    playerSession.onEvent(Events.event(null, Events.LOG_OUT));
                }
            }
        }
    }

    protected void handleJoinGame(Channel channel, Credentials credentials) {
        // Make sure game exists, game configured by Spring

        // Create player session
        PlayerSession playerSession = game.createPlayerSession();
        playerSession.setAttribute("credentials", credentials);
        LOG.debug("Sending GAME_LOG_IN_SUCCESS to channel {}.", channel);
        ChannelFuture future = channel.writeAndFlush(getLoginSuccessBuffer(credentials));
        connectToGame(game, playerSession, future);

        // Register session for connection
        registerSession(playerSession, credentials);
    }

    protected void connectToGame(final Game game, final PlayerSession playerSession, ChannelFuture future) {
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                Channel channel = future.channel();
                LOG.debug("Sending GAME_LOG_IN_SUCCESS to channel {} completed.", channel);

                if (future.isSuccess()) {
                    LOG.debug("Going to clear pipeline");
                    // Clear the existing pipeline
                    NettyUtils.clearPipeline(channel.pipeline());
                    // Set the socket channel on the session
                    SocketMessageSender sender = new SocketMessageSender(channel);
                    playerSession.setSender(sender);
                    // Connect the pipeline to the game.
                    game.connectSession(playerSession);

                    // Apply the protocol to session
                    LOG.debug("Protocol to be applied is: {}", protocol.getClass().getName());
                    protocol.applyProtocol(playerSession, true);
                    // Send the START event
                    // playerSession.onEvent(Events.event(null, Events.START));
                    // Login to set event handler
                    game.onLogin(playerSession);
                } else {
                    LOG.error("GAME_LOG_IN_SUCCESS message sending to client was failure, channel will be closed.");
                    channel.close();
                }
            }
        });
    }

    protected void registerSession(PlayerSession playerSession, Credentials credentials) {
        if (credentials != null) {
            sessionManagerService.putSession(credentials, playerSession);
        }
    }

    /**
     * Helper method
     */
    protected ByteBuf getLoginSuccessBuffer(Credentials credentials) {
        String token = AesGcmCipher.encrypt(credentials.getRandomKey());
        return Unpooled.wrappedBuffer(NettyUtils.createBufferForOpcode(Events.LOG_IN_SUCCESS), NettyUtils.writeString(token));
    }

    protected void closeChannelWithLoginFailure(Channel channel) {
        ChannelFuture future = channel.writeAndFlush(NettyUtils.createBufferForOpcode(Events.LOG_IN_FAILURE));
        future.addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Spring configuration
     */
    public Protocol getProtocol() {
        return protocol;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public Game getGame() {
        return game;
    }

    public void setGame(Game game) {
        this.game = game;
    }

    public SessionManagerService<Credentials> getSessionManagerService() {
        return sessionManagerService;
    }

    public void setSessionManagerService(SessionManagerService<Credentials> sessionManagerService) {
        this.sessionManagerService = sessionManagerService;
    }
}
