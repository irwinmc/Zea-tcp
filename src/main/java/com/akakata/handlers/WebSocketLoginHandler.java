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
 * 现代化 WebSocket 登录处理器（Java 21 重构版 - 纯事件驱动）
 * <p>
 * 与 {@link LoginHandler} 类似，但处理 WebSocket 协议。主要区别：
 * <ul>
 *   <li>接收 {@link WebSocketFrame} 而不是 {@link Event}</li>
 *   <li>需要 {@link #frameToEvent} 和 {@link #eventToFrame} 转换方法</li>
 *   <li>消息格式：[opcode:1byte] [payload:variable]</li>
 * </ul>
 * <p>
 * <b>WebSocket 消息格式：</b>
 * <pre>
 * BinaryWebSocketFrame {
 *   opcode:  1 byte  (事件类型，如 Events.LOG_IN)
 *   payload: N bytes (JSON 格式的数据，可选)
 * }
 * </pre>
 * <p>
 * <b>工作流程：</b>
 * <pre>
 * 1. channelRead0() 接收 WebSocketFrame
 *    ↓
 * 2. frameToEvent() 转换为 Event
 *    ↓
 * 3. 验证事件类型（只接受 LOG_IN）
 *    ↓
 * 4. LoginService.verify() 验证凭证
 *    ↓
 * 5. 如果失败 → 发送 LOG_IN_FAILURE 事件 → 关闭连接
 *    ↓
 * 6. 如果成功 → LoginService.createAndReplaceSession()
 *    ↓
 * 7. LoginService.generateToken()
 *    ↓
 * 8. 发送 LOG_IN_SUCCESS 事件 → 虚拟线程初始化会话
 *    ↓
 * 9. 完成
 * </pre>
 *
 * @author Kelvin
 * @see LoginHandler
 * @see LoginService
 * @since 0.7.8
 */
@Sharable
public class WebSocketLoginHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketLoginHandler.class);

    /**
     * 静态 ObjectMapper - 线程安全且避免重复创建
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Protocol protocol;
    private final Game game;
    private final LoginService loginService;

    @Inject
    public WebSocketLoginHandler(Protocol protocol,
                                 Game game,
                                 LoginService loginService) {
        this.protocol = protocol;
        this.game = game;
        this.loginService = loginService;
    }

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

        // 验证事件类型
        if (event.getType() != Events.LOG_IN) {
            LOG.error("Invalid event type {} from {}, expected LOG_IN",
                    event.getType(), channel.remoteAddress());
            sendLoginFailureAndClose(channel);
            return;
        }

        LOG.debug("WebSocket login attempt from {}", channel.remoteAddress());

        // ByteBufHolder 自动内存管理
        try (var bufferHolder = new ByteBufHolder(event.getSource())) {
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
     * 处理登录成功
     */
    private void handleLoginSuccess(Channel channel, PlayerSession session, String token) {
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
     * 初始化会话
     */
    private void initializeSession(Channel channel, PlayerSession session) {
        // 设置 MessageSender
        var sender = new SocketMessageSender(channel);
        session.setSender(sender);

        // 应用协议
        LOG.debug("Applying WebSocket protocol: {}", protocol.getClass().getSimpleName());
        protocol.applyProtocol(session);

        // 连接到游戏
        game.connectSession(session);

        // 登录
        game.onLogin(session);
    }

    /**
     * WebSocket 帧转换为 Event
     * <p>
     * 消息格式：[opcode:1byte] [payload:variable]
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
     * Event 转换为 WebSocket 帧
     * <p>
     * 使用静态 ObjectMapper
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
     * 发送登录失败消息并关闭连接
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
     * 清理资源并关闭
     */
    private void cleanupAndClose(PlayerSession session, Channel channel) {
        try {
            session.close();
        } catch (Exception e) {
            LOG.error("Error closing session", e);
        }
        channel.close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.error("Exception in WebSocketLoginHandler for {}", ctx.channel().id(), cause);
        ctx.close();
    }
}
