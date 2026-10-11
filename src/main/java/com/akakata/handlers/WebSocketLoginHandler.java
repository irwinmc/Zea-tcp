package com.akakata.handlers;

import com.akakata.app.Game;
import com.akakata.app.PlayerSession;
import com.akakata.communication.impl.SocketMessageSender;
import com.akakata.event.Event;
import com.akakata.event.Events;
import com.akakata.protocols.Protocol;
import com.akakata.service.LoginService;
import com.akakata.util.ByteBufHolder;
import com.akakata.util.NettyUtils;
import tools.jackson.databind.ObjectMapper;
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
 * Authenticates WebSocket login frames and installs the application event protocol.
 *
 * <p>Only binary login frames carry credentials. Session initialization is guarded
 * by the session monitor, and channel closure releases pending or connected sessions.
 *
 * @author Kelvin
 * @since 0.7.8
 * @see LoginHandler
 * @see LoginService
 */
@Sharable
public class WebSocketLoginHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketLoginHandler.class);

    /**
     * Shared JSON mapper for login-frame encoding.
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

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
     * Creates a web socket login handler instance with the supplied dependencies.
     *
     * @param protocol the protocol to install or associate
     * @param game the associated game
     * @param loginService the authentication coordinator
     */
    @Inject
    public WebSocketLoginHandler(Protocol protocol,
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
    public void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        var channel = ctx.channel();

        if (!(frame instanceof BinaryWebSocketFrame binaryFrame)) {
            LOG.warn("Received non-binary WebSocket frame from {}, closing", channel.remoteAddress());
            sendLoginFailureAndClose(channel);
            return;
        }

        // 转换为 Event
        var event = frameToEvent(binaryFrame);

        LOG.debug("WebSocket login attempt from {}", channel.remoteAddress());

        // ByteBufHolder 自动内存管理
        try (var bufferHolder = new ByteBufHolder(event.getSource())) {
            if (event.getType() != Events.LOG_IN) {
                LOG.error("Invalid event type {} from {}, expected LOG_IN",
                        event.getType(), channel.remoteAddress());
                sendLoginFailureAndClose(channel);
                return;
            }
            // LoginService 登录
            var result = loginService.login(bufferHolder.buffer(), game);

            if (result == null) {
                LOG.warn("WebSocket login failed from {}", channel.remoteAddress());
                sendLoginFailureAndClose(channel);
            } else {
                LOG.info("WebSocket login successful: sessionId={}", result.session().getId());
                handleLoginSuccess(channel, result.session(), result.token());
            }
        } catch (Exception e) {
            LOG.error("WebSocket login processing failed for {}", channel.remoteAddress(), e);
            sendLoginFailureAndClose(channel);
        }
    }

    /**
     * Writes a WebSocket success response and initializes the session after a successful write.
     *
     * <p>The current response carries only the success opcode; the token parameter is
     * not serialized by this method.
     *
     * @param channel the Netty transport channel
     * @param session the session associated with the operation
     * @param token the encrypted login token
     */
    private void handleLoginSuccess(Channel channel, PlayerSession session, String token) {
        channel.closeFuture().addListener(future -> cleanupAndClose(session, channel));
        try {
            // 创建登录成功帧
            var event = Events.event(null, Events.LOG_IN_SUCCESS);
            var frame = eventToFrame(event);

            channel.writeAndFlush(frame).addListener((ChannelFuture future) -> {
                if (future.isSuccess()) {
                    LOG.debug("WebSocket LOG_IN_SUCCESS sent to channel {}", channel.id());

                    try {
                        // WebSocket 不需要清理 pipeline
                        initializeSession(channel, session);

                        LOG.info("WebSocket session initialized: sessionId={}", session.getId());
                    } catch (Exception e) {
                        LOG.error("Failed to initialize WebSocket session", e);
                        cleanupAndClose(session, channel);
                    }
                } else {
                    LOG.error("Failed to send WebSocket LOG_IN_SUCCESS to {}", channel.id(), future.cause());
                    cleanupAndClose(session, channel);
                }
            });
        } catch (Exception e) {
            LOG.error("Failed to create WebSocket login success frame", e);
            cleanupAndClose(session, channel);
        }
    }

    /**
     * Attaches the socket sender, connects the game and installs the WebSocket protocol under the
     * session monitor.
     *
     * @param channel the Netty transport channel
     * @param session the session associated with the operation
     * @throws IllegalStateException if the channel or session is closed, or the game rejects the
     * connection
     */
    private void initializeSession(Channel channel, PlayerSession session) {
        // Share DefaultSession.close()'s monitor through sender, protocol and game installation.
        synchronized (session) {
            if (!channel.isActive() || session.isShuttingDown()) {
                throw new IllegalStateException("Login completed after session or channel closure");
            }
            session.setSender(new SocketMessageSender(channel));
            if (!game.connectSession(session)) {
                throw new IllegalStateException("Game rejected the login session");
            }
            LOG.debug("Applying WebSocket protocol: {}", protocol.getClass().getSimpleName());
            protocol.applyProtocol(session);
            game.onLogin(session);
        }
    }

    /**
     * Copies a binary frame's bytes after the opcode into an event payload.
     *
     * @param frame the inbound WebSocket frame
     * @return the event with a caller-owned payload buffer
     */
    private Event frameToEvent(BinaryWebSocketFrame frame) {
        ByteBuf buffer = frame.content();

        // 读取 opcode（1 字节）
        int opcode = buffer.readUnsignedByte();

        // 读取剩余数据（payload）
        ByteBuf data = buffer.readBytes(buffer.readableBytes());

        return Events.event(data, opcode);
    }

    /**
     * Encodes an opcode and optional length-prefixed JSON source as a binary frame.
     *
     * @param event the event to process
     * @return the caller-owned frame
     * @throws Exception if source serialization or frame encoding fails
     */
    private BinaryWebSocketFrame eventToFrame(Event event) throws Exception {
        ByteBuf opcode = NettyUtils.createBufferForOpcode(event.getType());

        if (event.getSource() != null) {
            // 序列化 source 为 JSON
            String json = OBJECT_MAPPER.writeValueAsString(event.getSource());
            ByteBuf payload = NettyUtils.writeString(json);
            return new BinaryWebSocketFrame(Unpooled.wrappedBuffer(opcode, payload));
        } else {
            return new BinaryWebSocketFrame(opcode);
        }
    }

    /**
     * Writes a login-failure response and closes the channel when the write completes.
     *
     * @param channel the Netty transport channel
     */
    private void sendLoginFailureAndClose(Channel channel) {
        try {
            var event = Events.event(null, Events.LOG_IN_FAILURE);
            var frame = eventToFrame(event);
            channel.writeAndFlush(frame).addListener(ChannelFutureListener.CLOSE);

            LOG.debug("Sent WebSocket LOG_IN_FAILURE to {}", channel.id());
        } catch (Exception e) {
            LOG.error("Failed to send WebSocket LOG_IN_FAILURE", e);
            channel.close();
        }
    }

    /**
     * Closes the session and closes the channel even if resource cleanup fails.
     *
     * @param session the session associated with the operation
     * @param channel the Netty transport channel
     */
    private void cleanupAndClose(PlayerSession session, Channel channel) {
        try {
            session.close();
        } catch (Exception e) {
            LOG.error("Error closing session", e);
        } finally {
            channel.close();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.error("Exception in WebSocketLoginHandler for {}", ctx.channel().id(), cause);
        ctx.close();
    }
}
