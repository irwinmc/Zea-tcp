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
 * 现代化登录处理器（Java 21 重构版 - 纯事件驱动）
 * <p>
 * 相比旧版本的改进：
 * <ul>
 *   <li><b>虚拟线程</b>：使用 {@code Thread.startVirtualThread()} 替代回调地狱</li>
 *   <li><b>try-with-resources</b>：使用 {@link ByteBufHolder} 自动管理 ByteBuf 生命周期</li>
 *   <li><b>职责分离</b>：业务逻辑封装在 {@link LoginService#login(ByteBuf, Game)}</li>
 *   <li><b>并发安全</b>：会话替换通过 Caffeine Cache 原子完成</li>
 *   <li><b>代码简洁</b>：核心登录逻辑只需 1 行代码</li>
 * </ul>
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
 * @see LoginService
 * @since 0.7.8
 */
@Sharable
public class LoginHandler extends SimpleChannelInboundHandler<Event> {

    private static final Logger LOG = LoggerFactory.getLogger(LoginHandler.class);

    private final Protocol protocol;
    private final Game game;
    private final LoginService loginService;

    @Inject
    public LoginHandler(@Named("tcpProtocol") Protocol protocol,
                        Game game,
                        LoginService loginService) {
        this.protocol = protocol;
        this.game = game;
        this.loginService = loginService;
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Event event) {
        var channel = ctx.channel();

        if (event.getType() != Events.LOG_IN) {
            LOG.error("Invalid event type {} from {}", event.getType(), channel.remoteAddress());
            sendLoginFailureAndClose(channel);
            return;
        }

        LOG.debug("Login attempt from {}", channel.remoteAddress());

        try (var bufferHolder = new ByteBufHolder(event.getSource())) {
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
     * 使用原版风格的 ChannelFutureListener
     */
    private void sendLoginSuccessAndConnect(Channel channel, PlayerSession session, String token) {
        ByteBuf buffer = null;
        try {
            buffer = createLoginSuccessBuffer(token);

            channel.writeAndFlush(buffer).addListener((ChannelFuture future) -> {
                if (future.isSuccess()) {
                    LOG.debug("LOG_IN_SUCCESS sent successfully");

                    try {
                        // 清理 pipeline
                        NettyUtils.clearPipeline(channel.pipeline());

                        // 设置 MessageSender
                        var sender = new SocketMessageSender(channel);
                        session.setSender(sender);

                        // 连接到游戏
                        game.connectSession(session);

                        // 应用协议
                        protocol.applyProtocol(session, true);

                        // 登录
                        game.onLogin(session);

                        LOG.info("Session initialized: sessionId={}", session.getId());
                    } catch (Exception e) {
                        LOG.error("Failed to initialize session", e);
                        session.close();
                        channel.close();
                    }
                } else {
                    LOG.error("Failed to send LOG_IN_SUCCESS", future.cause());
                    session.close();
                    channel.close();
                }
            });

            buffer = null; // 防止 finally 重复释放
        } finally {
            if (buffer != null) {
                buffer.release();
            }
        }
    }

    private void sendLoginFailureAndClose(Channel channel) {
        var buffer = NettyUtils.createBufferForOpcode(Events.LOG_IN_FAILURE);
        channel.writeAndFlush(buffer).addListener(ChannelFutureListener.CLOSE);
    }

    private ByteBuf createLoginSuccessBuffer(String token) {
        var opcode = NettyUtils.createBufferForOpcode(Events.LOG_IN_SUCCESS);
        var payload = NettyUtils.writeString(token);
        return Unpooled.wrappedBuffer(opcode, payload);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        AbstractNettyServer.ALL_CHANNELS.add(ctx.channel());
        LOG.debug("Channel active: id={}, total={}",
                ctx.channel().id(),
                AbstractNettyServer.ALL_CHANNELS.size());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.error("Exception in LoginHandler", cause);
        ctx.close();
    }
}
