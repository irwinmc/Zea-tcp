package com.akakata.handlers;

import com.akakata.app.Game;
import com.akakata.app.PlayerSession;
import com.akakata.communication.impl.SocketMessageSender;
import com.akakata.event.Event;
import com.akakata.event.Events;
import com.akakata.protocols.Protocol;
import com.akakata.security.Credentials;
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
 * <b>相比旧版本的改进：</b>
 * <ul>
 *   <li><b>虚拟线程</b>：使用 {@code Thread.startVirtualThread()} 替代回调</li>
 *   <li><b>try-with-resources</b>：使用 {@link ByteBufHolder} 自动管理 ByteBuf</li>
 *   <li><b>职责分离</b>：业务逻辑移至 {@link LoginService}</li>
 *   <li><b>并发安全</b>：会话替换通过 CaffeineSessionManager 原子完成</li>
 *   <li><b>事件驱动</b>：完全使用事件系统，无需 LoginResult 返回值类型</li>
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
 * @since 0.7.8
 * @see LoginHandler
 * @see LoginService
 */
@Sharable
public class WebSocketLoginHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketLoginHandler.class);

    /**
     * Jackson ObjectMapper（用于 JSON 序列化）
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Protocol protocol;
    private final Game game;
    private final LoginService loginService;

    /**
     * 构造函数（依赖注入）
     *
     * @param protocol     WebSocket 协议实现
     * @param game         游戏实例
     * @param loginService 登录服务
     */
    @Inject
    public WebSocketLoginHandler(Protocol protocol,
                                  Game game,
                                  LoginService loginService) {
        this.protocol = protocol;
        this.game = game;
        this.loginService = loginService;
    }

    // ==================== Netty 生命周期方法 ====================

    /**
     * 处理接收到的 WebSocket 帧
     * <p>
     * 使用 Java 21 特性重构：
     * <ul>
     *   <li>Pattern Matching for instanceof - 类型检查和转换</li>
     *   <li>{@link ByteBufHolder} - try-with-resources 自动释放 ByteBuf</li>
     *   <li>虚拟线程 - 异步初始化会话</li>
     * </ul>
     *
     * @param ctx   Channel 上下文
     * @param frame WebSocket 帧（应为 BinaryWebSocketFrame）
     */
    @Override
    public void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        var channel = ctx.channel();

        // 使用 Pattern Matching 检查帧类型（Java 21）
        if (!(frame instanceof BinaryWebSocketFrame binaryFrame)) {
            LOG.warn("Received non-binary WebSocket frame from {}, closing", channel.remoteAddress());
            closeChannelWithLoginFailure(channel);
            return;
        }

        // 转换为 Event
        var event = frameToEvent(binaryFrame);

        // 使用 try-with-resources 自动管理 ByteBuf
        try (var bufferHolder = new ByteBufHolder(event.getSource())) {
            var buffer = bufferHolder.buffer();

            // 验证事件类型
            if (event.getType() != Events.LOG_IN) {
                LOG.error("Invalid event type {} from {}, expected LOG_IN",
                        event.getType(), channel.remoteAddress());
                closeChannelWithLoginFailure(channel);
                return;
            }

            LOG.debug("WebSocket login attempt from {}", channel.remoteAddress());

            // 1. 验证凭证
            Credentials credentials = loginService.verify(buffer);
            if (credentials == null) {
                LOG.warn("Invalid credentials from {}", channel.remoteAddress());
                closeChannelWithLoginFailure(channel);
                return;
            }

            // 2. 创建会话
            PlayerSession session = loginService.createAndReplaceSession(credentials, game);

            // 3. 生成 token
            String token = loginService.generateToken(credentials);

            // 4. 发送登录成功消息并初始化会话
            LOG.info("WebSocket login successful: sessionId={}, channel={}", session.getId(), channel.id());
            sendLoginSuccessAndInitialize(channel, session, token);
        }
    }

    /**
     * 异常捕获
     *
     * @param ctx   Channel 上下文
     * @param cause 异常原因
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.error("Exception in WebSocketLoginHandler for channel {}", ctx.channel().id(), cause);
        ctx.close();
    }

    // ==================== 登录处理逻辑 ====================

    /**
     * 发送登录成功消息并初始化会话（虚拟线程 - Java 21）
     * <p>
     * 与 {@link LoginHandler#sendLoginSuccessAndInitialize} 类似，
     * 但使用 {@link #eventToFrame} 将 Event 转换为 WebSocketFrame。
     *
     * @param channel Netty Channel
     * @param session 玩家会话
     * @param token   加密后的 token
     */
    private void sendLoginSuccessAndInitialize(Channel channel, PlayerSession session, String token) {
        try {
            // 创建 LOG_IN_SUCCESS 事件
            var event = Events.event(null, Events.LOG_IN_SUCCESS);
            var frame = eventToFrame(event);

            // 发送 WebSocket 帧
            var sendFuture = channel.writeAndFlush(frame);

            // 在虚拟线程中等待发送完成并初始化会话（Java 21）
            Thread.startVirtualThread(() -> {
                try {
                    // 等待发送完成（不阻塞平台线程！）
                    sendFuture.await();

                    if (sendFuture.isSuccess()) {
                        LOG.debug("WebSocket LOG_IN_SUCCESS sent successfully to channel {}", channel.id());

                        // 初始化会话（WebSocket 不需要清理 pipeline）
                        initializeSession(channel, session);

                        LOG.info("WebSocket session initialized: sessionId={}, channel={}",
                                session.getId(), channel.id());
                    } else {
                        LOG.error("Failed to send WebSocket LOG_IN_SUCCESS to channel {}, closing",
                                channel.id(), sendFuture.cause());
                        channel.close();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOG.error("Interrupted while waiting for WebSocket send completion", e);
                    channel.close();
                } catch (Exception e) {
                    LOG.error("Failed to initialize WebSocket session for channel {}", channel.id(), e);
                    session.close();
                    channel.close();
                }
            });
        } catch (Exception e) {
            LOG.error("Failed to create WebSocket login success frame", e);
            channel.close();
        }
    }

    /**
     * 初始化会话
     *
     * @param channel Netty Channel
     * @param session 玩家会话
     */
    private void initializeSession(Channel channel, PlayerSession session) {
        // 设置 MessageSender
        var sender = new SocketMessageSender(channel);
        session.setSender(sender);

        // 连接到 Game
        game.connectSession(session);

        // 应用 Protocol
        LOG.debug("Applying WebSocket protocol: {}", protocol.getClass().getSimpleName());
        protocol.applyProtocol(session);

        // 调用 onLogin（添加 EventHandler）
        game.onLogin(session);
    }

    // ==================== 协议转换方法 ====================

    /**
     * WebSocket 帧转换为 Event
     * <p>
     * 消息格式：[opcode:1byte] [payload:variable]
     * <p>
     * <b>注意：</b>返回的 Event 的 source 是新创建的 ByteBuf，需要释放。
     *
     * @param frame BinaryWebSocketFrame
     * @return Event
     */
    private Event frameToEvent(BinaryWebSocketFrame frame) {
        ByteBuf buffer = frame.content();

        // 读取 opcode（1 字节）
        int opcode = buffer.readUnsignedByte();

        // 读取剩余数据（payload）
        ByteBuf data = buffer.readBytes(buffer.readableBytes());

        // 创建 Event
        return Events.event(data, opcode);
    }

    /**
     * Event 转换为 WebSocket 帧
     * <p>
     * 消息格式：[opcode:1byte] [payload:JSON]
     * <p>
     * <b>注意：</b>如果 event.getSource() 不为 null，会将其序列化为 JSON。
     *
     * @param event Event
     * @return BinaryWebSocketFrame
     * @throws Exception 如果 JSON 序列化失败
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
     *
     * @param channel Netty Channel
     */
    private void closeChannelWithLoginFailure(Channel channel) {
        try {
            var event = Events.event(null, Events.LOG_IN_FAILURE);
            var frame = eventToFrame(event);
            var future = channel.writeAndFlush(frame);
            future.addListener(ChannelFutureListener.CLOSE);

            LOG.debug("Sent WebSocket LOG_IN_FAILURE to channel {}, closing", channel.id());
        } catch (Exception e) {
            LOG.error("Failed to send WebSocket LOG_IN_FAILURE", e);
            channel.close();
        }
    }
}
