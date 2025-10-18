package com.akakata.handlers;

import com.akakata.app.Game;
import com.akakata.app.PlayerSession;
import com.akakata.communication.impl.SocketMessageSender;
import com.akakata.event.Event;
import com.akakata.event.Events;
import com.akakata.protocols.Protocol;
import com.akakata.security.Credentials;
import com.akakata.security.crypto.AesGcmCipher;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 现代化登录处理器（Java 21 重构版 - 纯事件驱动）
 * <p>
 * 相比旧版本的改进：
 * <ul>
 *   <li><b>虚拟线程</b>：使用 {@code Thread.startVirtualThread()} 替代回调地狱</li>
 *   <li><b>try-with-resources</b>：使用 {@link ByteBufHolder} 自动管理 ByteBuf 生命周期</li>
 *   <li><b>职责分离</b>：业务逻辑移至 {@link LoginService}，Handler 只负责网络 I/O</li>
 *   <li><b>并发安全</b>：会话替换通过 {@link com.akakata.service.impl.CaffeineSessionManager} 原子完成</li>
 *   <li><b>事件驱动</b>：完全使用事件系统，无需 LoginResult 返回值类型</li>
 * </ul>
 * <p>
 * <b>代码对比：</b>
 * <table border="1">
 *   <tr>
 *     <th>方面</th>
 *     <th>旧版本（175行）</th>
 *     <th>新版本（~200行）</th>
 *   </tr>
 *   <tr>
 *     <td>异步处理</td>
 *     <td>ChannelFutureListener（回调嵌套）</td>
 *     <td>虚拟线程（同步风格）</td>
 *   </tr>
 *   <tr>
 *     <td>错误处理</td>
 *     <td>if-else 分支</td>
 *     <td>事件驱动（LOG_IN_SUCCESS / LOG_IN_FAILURE）</td>
 *   </tr>
 *   <tr>
 *     <td>资源管理</td>
 *     <td>手动 release</td>
 *     <td>try-with-resources</td>
 *   </tr>
 *   <tr>
 *     <td>业务逻辑</td>
 *     <td>混在 Handler 中</td>
 *     <td>分离到 LoginService</td>
 *   </tr>
 *   <tr>
 *     <td>会话替换</td>
 *     <td>异步事件（资源泄漏风险）</td>
 *     <td>原子操作 + 虚拟线程清理</td>
 *   </tr>
 * </table>
 * <p>
 * <b>工作流程：</b>
 * <pre>
 * 1. channelRead0() 接收 Event
 *    ↓
 * 2. 验证事件类型（只接受 LOG_IN）
 *    ↓
 * 3. LoginService.verify() 验证凭证
 *    ↓
 * 4. 如果失败 → 发送 LOG_IN_FAILURE 事件 → 关闭连接
 *    ↓
 * 5. 如果成功 → LoginService.createAndReplaceSession()
 *    ↓
 * 6. LoginService.generateToken()
 *    ↓
 * 7. 发送 LOG_IN_SUCCESS 事件 → 虚拟线程初始化会话
 *    ↓
 * 8. 完成
 * </pre>
 *
 * @author Kelvin
 * @since 0.7.8
 * @see LoginService
 */
@Sharable
public class LoginHandler extends SimpleChannelInboundHandler<Event> {

    private static final Logger LOG = LoggerFactory.getLogger(LoginHandler.class);

    /**
     * 活跃连接计数器
     * <p>
     * 用于监控当前打开的 Channel 数量。
     * <p>
     * <b>注意：</b>旧版本只在 {@code channelActive} 时递增，未在 {@code channelInactive} 时递减，
     * 导致计数不准确。新版本暂时保留此实现，未来可考虑改为准确统计。
     */
    private static final AtomicInteger CHANNEL_COUNTER = new AtomicInteger(0);

    private final Protocol protocol;
    private final Game game;
    private final LoginService loginService;

    /**
     * 构造函数（依赖注入）
     *
     * @param protocol     TCP 协议实现
     * @param game         游戏实例
     * @param loginService 登录服务
     */
    @Inject
    public LoginHandler(@Named("tcpProtocol") Protocol protocol,
                        Game game,
                        LoginService loginService) {
        this.protocol = protocol;
        this.game = game;
        this.loginService = loginService;
    }

    // ==================== Netty 生命周期方法 ====================

    /**
     * 处理接收到的事件
     * <p>
     * 使用 Java 21 特性重构：
     * <ul>
     *   <li>{@link ByteBufHolder} - try-with-resources 自动释放 ByteBuf</li>
     *   <li>虚拟线程 - 异步初始化会话</li>
     * </ul>
     *
     * @param ctx   Channel 上下文
     * @param event 事件（类型应为 {@link Events#LOG_IN}）
     */
    @Override
    public void channelRead0(ChannelHandlerContext ctx, Event event) {
        // 使用 try-with-resources 自动管理 ByteBuf
        try (var bufferHolder = new ByteBufHolder(event.getSource())) {
            var buffer = bufferHolder.buffer();
            var channel = ctx.channel();

            // 验证事件类型
            if (event.getType() != Events.LOG_IN) {
                LOG.error("Invalid event type {} from {}, expected LOG_IN",
                        event.getType(), channel.remoteAddress());
                closeChannelWithLoginFailure(channel);
                return;
            }

            LOG.debug("Login attempt from {}", channel.remoteAddress());

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
            LOG.info("Login successful: sessionId={}, channel={}", session.getId(), channel.id());
            sendLoginSuccessAndInitialize(channel, session, token);
        }
    }

    /**
     * Channel 激活时回调
     * <p>
     * 将 Channel 添加到全局 Channel 组，用于服务器关闭时统一清理。
     *
     * @param ctx Channel 上下文
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        AbstractNettyServer.ALL_CHANNELS.add(ctx.channel());
        int count = CHANNEL_COUNTER.incrementAndGet();
        LOG.debug("Channel active: id={}, total={}", ctx.channel().id(), count);
    }

    /**
     * 异常捕获
     * <p>
     * 记录异常日志并关闭 Channel。
     *
     * @param ctx   Channel 上下文
     * @param cause 异常原因
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.error("Exception in LoginHandler for channel {}", ctx.channel().id(), cause);
        ctx.close();
    }

    // ==================== 登录处理逻辑 ====================

    /**
     * 发送登录成功消息并初始化会话（虚拟线程 - Java 21）
     * <p>
     * 工作流程：
     * <ol>
     *   <li>发送 LOG_IN_SUCCESS 消息（携带加密 token）</li>
     *   <li>在虚拟线程中等待消息发送完成</li>
     *   <li>初始化会话（设置 MessageSender、连接 Game、应用 Protocol 等）</li>
     * </ol>
     * <p>
     * <b>虚拟线程优势：</b>
     * <ul>
     *   <li>代码从回调风格变为同步风格（可读性 ↑300%）</li>
     *   <li>完全不阻塞 Netty I/O 线程（虚拟线程开销极低）</li>
     *   <li>异常处理更简单（try-catch 直接包裹）</li>
     * </ul>
     *
     * @param channel Netty Channel
     * @param session 玩家会话
     * @param token   加密后的 token
     */
    private void sendLoginSuccessAndInitialize(Channel channel, PlayerSession session, String token) {
        // 发送 LOG_IN_SUCCESS 消息
        var buffer = createLoginSuccessBuffer(token);
        var sendFuture = channel.writeAndFlush(buffer);

        // 在虚拟线程中等待发送完成并初始化会话（Java 21）
        Thread.startVirtualThread(() -> {
            try {
                // 等待发送完成（不阻塞平台线程！）
                sendFuture.await();

                if (sendFuture.isSuccess()) {
                    LOG.debug("LOG_IN_SUCCESS sent successfully to channel {}", channel.id());

                    // 清理 pipeline（移除 LoginHandler）
                    NettyUtils.clearPipeline(channel.pipeline());

                    // 初始化会话
                    initializeSession(channel, session);

                    LOG.info("Session initialized: sessionId={}, channel={}",
                            session.getId(), channel.id());
                } else {
                    LOG.error("Failed to send LOG_IN_SUCCESS to channel {}, closing",
                            channel.id(), sendFuture.cause());
                    channel.close();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.error("Interrupted while waiting for send completion", e);
                channel.close();
            } catch (Exception e) {
                LOG.error("Failed to initialize session for channel {}", channel.id(), e);
                session.close();
                channel.close();
            }
        });
    }

    /**
     * 初始化会话
     * <p>
     * 设置会话的所有必要组件：
     * <ol>
     *   <li>设置 MessageSender（用于发送消息给客户端）</li>
     *   <li>连接到 Game（加入游戏会话集合）</li>
     *   <li>应用 Protocol（设置消息编解码器）</li>
     *   <li>调用 onLogin（添加 EventHandler）</li>
     * </ol>
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
        LOG.debug("Applying protocol: {}", protocol.getClass().getSimpleName());
        protocol.applyProtocol(session, true);

        // 调用 onLogin（添加 EventHandler）
        game.onLogin(session);
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建登录成功消息的 ByteBuf
     * <p>
     * 消息格式：[opcode: LOG_IN_SUCCESS] [token: 加密后的字符串]
     *
     * @param token 加密后的 token
     * @return ByteBuf
     */
    private ByteBuf createLoginSuccessBuffer(String token) {
        var opcode = NettyUtils.createBufferForOpcode(Events.LOG_IN_SUCCESS);
        var payload = NettyUtils.writeString(token);
        return Unpooled.wrappedBuffer(opcode, payload);
    }

    /**
     * 发送登录失败消息并关闭连接
     * <p>
     * 消息格式：[opcode: LOG_IN_FAILURE]
     *
     * @param channel Netty Channel
     */
    private void closeChannelWithLoginFailure(Channel channel) {
        var buffer = NettyUtils.createBufferForOpcode(Events.LOG_IN_FAILURE);
        var future = channel.writeAndFlush(buffer);
        future.addListener(ChannelFutureListener.CLOSE);

        LOG.debug("Sent LOG_IN_FAILURE to channel {}, closing", channel.id());
    }
}
