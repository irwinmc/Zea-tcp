package com.akakata.handlers;

import com.akakata.app.Game;
import com.akakata.app.PlayerSession;
import com.akakata.app.Session;
import com.akakata.communication.impl.SocketMessageSender;
import com.akakata.event.Event;
import com.akakata.event.Events;
import com.akakata.protocols.Protocol;
import com.akakata.security.Credentials;
import com.akakata.service.SessionManagerService;
import com.akakata.util.NettyUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

/**
 * Web socket 协议拥有消息边界
 * 因此不需要处理粘包和半包的问题
 *
 * @author Kelvin
 */
@Sharable
public class WebSocketLoginHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketLoginHandler.class);

    private final Protocol protocol;
    private final Game game;
    private final SessionManagerService<Credentials> sessionManagerService;

    @Inject
    public WebSocketLoginHandler(Protocol protocol,
                                 Game game,
                                 SessionManagerService<Credentials> sessionManagerService) {
        this.protocol = protocol;
        this.game = game;
        this.sessionManagerService = sessionManagerService;
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
        final Channel channel = ctx.channel();
        final Event event = frameToEvent(frame);
        final ByteBuf object = (ByteBuf) event.getSource();

        try {
            // Check event type
            int type = event.getType();
            if (type == Events.LOG_IN) {
                LOG.debug("Login attempt from {}", channel.remoteAddress());

                // Play handle login
                Credentials credentials = sessionManagerService.verify(object);
                handleLogin(channel, credentials);
            } else {
                LOG.error("Invalid web socket frame.");

                closeChannelWithLoginFailure(channel);
            }
        } finally {
            object.release();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOG.error("Exception during network communication: {}.", ctx.channel(), cause);
        ctx.channel().close();
    }

    protected void handleLogin(Channel channel, Credentials credentials) throws Exception {
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

    protected void handleJoinGame(Channel channel, Credentials credentials) throws Exception {
        // Create player session
        PlayerSession playerSession = game.createPlayerSession();
        playerSession.setAttribute("credentials", credentials);
        LOG.debug("Sending GAME_LOG_IN_SUCCESS to channel {}.", channel);
        ChannelFuture future = channel.writeAndFlush(eventToFrame(Events.event(null, Events.LOG_IN_SUCCESS)));
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
                    // LOG.debug("Going to clear pipeline");
                    // Clear the existing pipeline
                    // NettyUtils.clearPipeline(channel.pipeline());
                    // Set the socket channel on the session
                    SocketMessageSender sender = new SocketMessageSender(channel);
                    playerSession.setSender(sender);
                    // Connect the pipeline to the game.
                    game.connectSession(playerSession);

                    // Apply the protocol to session
                    LOG.debug("Protocol to be applied is: {}", protocol.getClass().getName());
                    protocol.applyProtocol(playerSession);
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

    protected void closeChannelWithLoginFailure(Channel channel) throws Exception {
        // Close the connection as soon as the error message is sent.
        ChannelFuture future = channel.writeAndFlush(eventToFrame(Events.event(null, Events.LOG_IN_FAILURE)));
        future.addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Web socket frame to Event
     *
     * @param frame
     * @return
     */
    protected Event frameToEvent(WebSocketFrame frame) {
        // Binary web socket frame
        if (frame instanceof BinaryWebSocketFrame) {
            ByteBuf buffer = frame.content();
            int opcode = buffer.readUnsignedByte();
            ByteBuf data = buffer.readBytes(buffer.readableBytes());
            // buffer.release();
            return Events.event(data, opcode);
        }
        return Events.event(null, Events.ANY);
    }

    /**
     * Event to binary web socket frame
     * Using JSON format content
     *
     * @param event
     * @return
     */
    protected BinaryWebSocketFrame eventToFrame(Event event) throws Exception {
        ByteBuf opcode = NettyUtils.createBufferForOpcode(event.getType());
        if (event.getSource() != null) {
            ByteBuf payload = NettyUtils.writeString(new ObjectMapper().writeValueAsString(event.getSource()));
            return new BinaryWebSocketFrame(Unpooled.wrappedBuffer(opcode, payload));
        } else {
            return new BinaryWebSocketFrame(opcode);
        }
    }
}
