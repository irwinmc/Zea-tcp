package com.akakata.service;

import com.akakata.app.Game;
import com.akakata.app.PlayerSession;
import com.akakata.security.Credentials;
import com.akakata.security.crypto.AesGcmCipher;
import com.akakata.service.impl.CaffeineSessionManager;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

/**
 * 登录服务
 * <p>
 * 从 {@link com.akakata.handlers.LoginHandler} 中分离出来的业务逻辑层，
 * 负责处理登录流程的核心逻辑：
 * <ul>
 *   <li>认证凭证（{@link #verify(ByteBuf)}）</li>
 *   <li>创建会话（{@link #createAndReplaceSession(Credentials, Game)}）</li>
 *   <li>替换旧会话（{@link CaffeineSessionManager#replaceSession}）</li>
 *   <li>生成加密 token（{@link #generateToken(Credentials)}）</li>
 * </ul>
 * <p>
 * <b>职责边界：</b>
 * <pre>
 * LoginHandler (网络层)
 *   ↓ 接收 ByteBuf
 * LoginService (业务层)
 *   ↓ 验证凭证、创建会话、生成 token
 * LoginHandler (网络层)
 *   ↓ 触发事件（LOG_IN_SUCCESS / LOG_IN_FAILURE）
 * </pre>
 * <p>
 * <b>使用示例：</b>
 * <pre>{@code
 * // 在 LoginHandler 中使用
 * @Inject
 * private LoginService loginService;
 *
 * @Override
 * protected void channelRead0(ChannelHandlerContext ctx, Event event) {
 *     try (var holder = new ByteBufHolder(event.getSource())) {
 *         // 1. 验证凭证
 *         Credentials credentials = loginService.verify(holder.buffer());
 *         if (credentials == null) {
 *             // 触发 LOG_IN_FAILURE 事件
 *             ctx.fireUserEventTriggered(Events.event(null, Events.LOG_IN_FAILURE));
 *             return;
 *         }
 *
 *         // 2. 创建会话
 *         PlayerSession session = loginService.createAndReplaceSession(credentials, game);
 *
 *         // 3. 生成 token
 *         String token = loginService.generateToken(credentials);
 *
 *         // 4. 触发 LOG_IN_SUCCESS 事件
 *         ctx.fireUserEventTriggered(Events.event(token, Events.LOG_IN_SUCCESS));
 *     }
 * }
 * }</pre>
 * <p>
 * <b>对比旧实现：</b>
 * <table border="1">
 *   <tr>
 *     <th>方面</th>
 *     <th>旧实现（LoginHandler）</th>
 *     <th>新实现（LoginService）</th>
 *   </tr>
 *   <tr>
 *     <td>职责</td>
 *     <td>网络 + 业务 + 加密混在一起</td>
 *     <td>只负责业务逻辑</td>
 *   </tr>
 *   <tr>
 *     <td>可测试性</td>
 *     <td>依赖 Netty Channel，难以单测</td>
 *     <td>纯业务逻辑，易于单测</td>
 *   </tr>
 *   <tr>
 *     <td>错误处理</td>
 *     <td>回调嵌套，异常处理分散</td>
 *     <td>返回 null 表示失败（简单明了）</td>
 *   </tr>
 *   <tr>
 *     <td>并发安全</td>
 *     <td>synchronized + 异步事件（不可控）</td>
 *     <td>Caffeine 原子操作 + 虚拟线程</td>
 *   </tr>
 * </table>
 *
 * @author Kelvin
 * @since 0.7.8
 */
public class LoginService {

    private static final Logger LOG = LoggerFactory.getLogger(LoginService.class);

    /**
     * 会话管理器（使用 Caffeine 实现）
     */
    private final CaffeineSessionManager sessionManager;

    /**
     * 构造函数（依赖注入）
     *
     * @param sessionManager 会话管理器
     */
    @Inject
    public LoginService(SessionManagerService<Credentials> sessionManager) {
        // 如果注入的是旧的 SimpleSessionManagerServiceImpl，会有类型转换异常
        // 这是有意为之，强制使用新的 CaffeineSessionManager
        this.sessionManager = (CaffeineSessionManager) sessionManager;
        LOG.info("LoginService initialized with CaffeineSessionManager");
    }

    /**
     * 验证凭证
     * <p>
     * 从客户端发送的 ByteBuf 中解析并验证凭证（token）。
     * <p>
     * <b>返回值：</b>
     * <ul>
     *   <li>验证成功：返回 {@link Credentials} 对象</li>
     *   <li>验证失败：返回 null</li>
     * </ul>
     *
     * @param buffer 客户端发送的认证数据（包含 token）
     * @return 验证通过返回 Credentials，失败返回 null
     */
    public Credentials verify(ByteBuf buffer) {
        try {
            Credentials credentials = sessionManager.verify(buffer);
            if (credentials == null) {
                LOG.warn("Invalid credentials: buffer is empty or token is blank");
            }
            return credentials;
        } catch (Exception e) {
            LOG.error("Failed to verify credentials", e);
            return null;
        }
    }

    /**
     * 创建新会话并替换旧会话
     * <p>
     * 完整的会话创建流程，包括：
     * <ol>
     *   <li>创建新会话（{@link Game#createPlayerSession()}）</li>
     *   <li>设置凭证属性</li>
     *   <li>替换旧会话（{@link CaffeineSessionManager#replaceSession}）</li>
     * </ol>
     * <p>
     * <b>并发安全：</b>
     * <ul>
     *   <li>使用 {@link CaffeineSessionManager#replaceSession} 原子替换会话</li>
     *   <li>旧会话在虚拟线程中异步清理，不阻塞登录流程</li>
     * </ul>
     *
     * @param credentials 凭证
     * @param game        游戏实例（用于创建会话）
     * @return 新创建的会话
     */
    public PlayerSession createAndReplaceSession(Credentials credentials, Game game) {
        try {
            // 1. 创建新会话
            PlayerSession newSession = game.createPlayerSession();
            newSession.setAttribute("credentials", credentials);

            // 2. 替换旧会话（原子操作）
            PlayerSession oldSession = sessionManager.replaceSession(credentials, newSession);
            if (oldSession != null) {
                LOG.warn("Replaced old session: credentials={}, oldSessionId={}, newSessionId={}",
                        credentials, oldSession.getId(), newSession.getId());
            }

            LOG.info("Session created: credentials={}, sessionId={}", credentials, newSession.getId());
            return newSession;

        } catch (Exception e) {
            LOG.error("Failed to create session for credentials: {}", credentials, e);
            throw new RuntimeException("Session creation failed", e);
        }
    }

    /**
     * 生成加密 token
     * <p>
     * 使用 {@link AesGcmCipher} 加密凭证的 randomKey，返回加密后的 token。
     * 这个 token 会发送给客户端，用于后续请求的认证。
     * <p>
     * <b>安全性：</b>
     * <ul>
     *   <li>使用 AES-GCM 加密（AEAD 模式）</li>
     *   <li>randomKey 是随机生成的8字节字符串（{@link com.akakata.security.SimpleCredentials}）</li>
     *   <li>每次登录生成新的 randomKey</li>
     * </ul>
     *
     * @param credentials 凭证
     * @return 加密后的 token
     * @throws RuntimeException 如果加密失败
     */
    public String generateToken(Credentials credentials) {
        try {
            String randomKey = credentials.getRandomKey();
            return AesGcmCipher.encrypt(randomKey);
        } catch (Exception e) {
            LOG.error("Failed to generate token for credentials: {}", credentials, e);
            throw new RuntimeException("Token generation failed", e);
        }
    }

    /**
     * 获取会话管理器的统计信息
     * <p>
     * 用于监控和调试，返回 Caffeine 缓存的统计数据。
     * <p>
     * <b>示例：</b>
     * <pre>{@code
     * var stats = loginService.getSessionStats();
     * LOG.info("Session cache: hit rate={}, size={}",
     *          stats.hitRate(), loginService.getSessionCount());
     * }</pre>
     *
     * @return 缓存统计
     */
    public com.github.benmanes.caffeine.cache.stats.CacheStats getSessionStats() {
        return sessionManager.getStats();
    }

    /**
     * 获取当前会话数量
     *
     * @return 会话总数
     */
    public long getSessionCount() {
        return sessionManager.size();
    }

    /**
     * 强制清理指定凭证的会话
     * <p>
     * 手动移除会话，通常用于管理员踢人、封号等场景。
     *
     * @param credentials 凭证
     * @return true 如果会话存在并被移除
     */
    public boolean kickSession(Credentials credentials) {
        boolean removed = sessionManager.removeSession(credentials);
        if (removed) {
            LOG.info("Session kicked: credentials={}", credentials);
        }
        return removed;
    }
}
