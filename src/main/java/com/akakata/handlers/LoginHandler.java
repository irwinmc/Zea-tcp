package com.akakata.handlers;

import com.akakata.app.Game;
import com.akakata.app.PlayerSession;
import com.akakata.communication.impl.SocketMessageSender;
import com.akakata.event.Event;
import com.akakata.event.Events;
import com.akakata.protocols.Protocol;
import com.akakata.server.impl.AbstractNettyServer;
import com.akakata.service.LoginService;
import com.akakata.util.ByteBufHolder;
import com.akakata.util.NettyUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.ChannelHandler.Sharable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Authenticates TCP login events and installs the selected session protocol.
 *
 * <p>Protocol installation begins after the success response is written. The
 * session monitor coordinates initialization with closure, and a close-future
 * listener releases sessions disconnected before or after that write completes.
 *
 * @author Kelvin
 * @since 0.7.8
 * @see LoginService
 */
@Sharable
public class LoginHandler extends SimpleChannelInboundHandler<Event> {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(LoginHandler.class);

    /**
     * Protocol associated with the session or installed after authentication.
     */
    private final Protocol protocol;

    /**
     * Game associated with this session or component.
     */
    private final Game game;

    /**
     * Authentication coordinator used to create and replace player sessions.
     */
    private final LoginService loginService;

    /**
     * Creates a login handler instance with the supplied dependencies.
     *
     * @param protocol the protocol to install or associate
     * @param game the associated game
     * @param loginService the authentication coordinator
     */
    @Inject
    public LoginHandler(@Named("tcpProtocol") Protocol protocol,
                        Game game,
                        LoginService loginService) {
        this.protocol = protocol;
        this.game = game;
        this.loginService = loginService;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void channelRead0(ChannelHandlerContext ctx, Event event) {
        var channel = ctx.channel();

        LOG.debug("Login attempt from {}", channel.remoteAddress());

        try (var bufferHolder = new ByteBufHolder(event.getSource())) {
            if (event.getType() != Events.LOG_IN) {
                LOG.error("Invalid event type {} from {}", event.getType(), channel.remoteAddress());
                sendLoginFailureAndClose(channel);
                return;
            }
            var result = loginService.login(bufferHolder.buffer(), game);

            if (result == null) {
                LOG.warn("Login failed from {}", channel.remoteAddress());
                sendLoginFailureAndClose(channel);
            } else {
                LOG.info("Login successful: sessionId={}", result.session().getId());
                sendLoginSuccessAndConnect(channel, result.session(), result.token());
            }
        } catch (Exception e) {
            LOG.error("Login processing failed", e);
            sendLoginFailureAndClose(channel);
        }
    }

    /**
     * Writes a login-success response and installs the session protocol only after the write
     * succeeds.
     *
     * <p>A channel close listener releases the session even when disconnection occurs
     * before the write finishes. Initialization takes the same monitor as session closure.
     *
     * @param channel the Netty transport channel
     * @param session the session associated with the operation
     * @param token the encrypted login token
     */
    private void sendLoginSuccessAndConnect(Channel channel, PlayerSession session, String token) {
        // Covers disconnects both before the login write completes and after protocol installation.
        channel.closeFuture().addListener(future -> closeSession(session, channel));
        ByteBuf buffer = null;
        try {
            buffer = createLoginSuccessBuffer(token);

            channel.writeAndFlush(buffer).addListener((ChannelFuture future) -> {
                if (future.isSuccess()) {
                    LOG.debug("LOG_IN_SUCCESS sent successfully");

                    try {
                        // Share DefaultSession.close()'s monitor throughout login initialization.
                        synchronized (session) {
                            if (!channel.isActive() || session.isShuttingDown()) {
                                throw new IllegalStateException("Login completed after session or channel closure");
                            }
                            NettyUtils.clearPipeline(channel.pipeline());
                            session.setSender(new SocketMessageSender(channel));
                            if (!game.connectSession(session)) {
                                throw new IllegalStateException("Game rejected the login session");
                            }
                            protocol.applyProtocol(session, true);
                            game.onLogin(session);
                        }

                        LOG.info("Session initialized: sessionId={}", session.getId());
                    } catch (Exception e) {
                        LOG.error("Failed to initialize session", e);
                        closeSession(session, channel);
                    }
                } else {
                    LOG.error("Failed to send LOG_IN_SUCCESS", future.cause());
                    closeSession(session, channel);
                }
            });

            buffer = null; // 防止 finally 重复释放
        } finally {
            if (buffer != null) {
                buffer.release();
            }
        }
    }

    /**
     * Closes session resources and closes the channel even if session cleanup throws.
     *
     * @param session the session associated with the operation
     * @param channel the Netty transport channel
     */
    private void closeSession(PlayerSession session, Channel channel) {
        try {
            session.close();
        } catch (Exception e) {
            LOG.error("Error closing login session", e);
        } finally {
            channel.close();
        }
    }

    /**
     * Writes a login-failure response and closes the channel when the write completes.
     *
     * @param channel the Netty transport channel
     */
    private void sendLoginFailureAndClose(Channel channel) {
        var buffer = NettyUtils.createBufferForOpcode(Events.LOG_IN_FAILURE);
        channel.writeAndFlush(buffer).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Allocates a login-success opcode followed by a length-prefixed token.
     *
     * @param token the encrypted login token
     * @return the caller-owned response buffer
     */
    private ByteBuf createLoginSuccessBuffer(String token) {
        var opcode = NettyUtils.createBufferForOpcode(Events.LOG_IN_SUCCESS);
        var payload = NettyUtils.writeString(token);
        return Unpooled.wrappedBuffer(opcode, payload);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        AbstractNettyServer.ALL_CHANNELS.add(ctx.channel());
        LOG.debug("Channel active: id={}, total={}",
                ctx.channel().id(),
                AbstractNettyServer.ALL_CHANNELS.size());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.error("Exception in LoginHandler", cause);
        ctx.close();
    }
}
