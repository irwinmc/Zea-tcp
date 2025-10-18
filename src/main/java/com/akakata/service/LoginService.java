package com.akakata.service;

import com.akakata.app.Game;
import com.akakata.app.PlayerSession;
import com.akakata.security.Credentials;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Objects;

/**
 * 登录服务
 * <p>
 * 从 {@link com.akakata.handlers.LoginHandler} 中分离出来的业务逻辑层。
 * <p>
 * <b>职责边界：</b>
 * <pre>
 * LoginHandler (网络层)
 *   ↓ 接收 ByteBuf
 * LoginService (业务层)
 *   ↓ 验证凭证、踢出旧会话、创建新会话、生成 token
 * LoginHandler (网络层)
 *   ↓ 发送 LOG_IN_SUCCESS / LOG_IN_FAILURE
 * </pre>
 * <p>
 * <b>使用示例：</b>
 * <pre>{@code
 * // 在 LoginHandler 中
 * try (var holder = new ByteBufHolder(event.getSource())) {
 *     LoginResult result = loginService.login(holder.buffer(), game);
 *     handleLoginSuccess(channel, result.session(), result.token());
 * } catch (LoginException e) {
 *     handleLoginFailure(channel, e.getReason());
 * }
 * }</pre>
 *
 * @author Kelvin
 * @since 0.7.8
 */
public class LoginService {

    private static final Logger LOG = LoggerFactory.getLogger(LoginService.class);

    /**
     * 登录结果
     * <p>
     * 封装登录成功后的会话和 token。
     *
     * @param session 玩家会话
     * @param token   加密后的 token
     */
    public record LoginResult(PlayerSession session, String token) {
        public LoginResult {
            Objects.requireNonNull(session, "session cannot be null");
            Objects.requireNonNull(token, "token cannot be null");
        }
    }

    private final SessionService sessionService;
    private final SecurityService securityService;

    /**
     * 构造函数（依赖注入）
     *
     * @param sessionService  会话管理服务
     * @param securityService 安全服务（Token 生成）
     */
    @Inject
    public LoginService(SessionService sessionService, SecurityService securityService) {
        this.sessionService = sessionService;
        this.securityService = securityService;
        LOG.info("LoginService initialized");
    }

    /**
     * 完整的登录流程（改进版）
     * <p>
     * 工作流程：
     * <ol>
     *   <li>验证凭证 ({@link #verify(ByteBuf)})</li>
     *   <li>创建新会话 ({@link #createSession(Credentials, Game)})</li>
     *   <li>替换旧会话 </li>
     *   <li>生成 token ({@link SecurityService#generateToken(Credentials)})</li>
     * </ol>
     * <p>
     * <b>使用示例：</b>
     * <pre>{@code
     * try {
     *     LoginResult result = loginService.login(buffer, game);
     *     sendLoginSuccess(channel, result.session(), result.token());
     * } catch (LoginException e) {
     *     switch (e.getReason()) {
     *         case INVALID_CREDENTIALS -> sendLoginFailure(channel, "Invalid credentials");
     *         case SYSTEM_ERROR -> sendLoginFailure(channel, "Internal server error");
     *     }
     * }
     * }</pre>
     *
     * @param buffer 客户端发送的认证数据
     * @param game   游戏实例
     * @return 登录成功返回 {@link LoginResult}
     */
    public LoginResult login(ByteBuf buffer, Game game) {
        try {
            // 1. 验证凭证
            Credentials credentials = verify(buffer);
            if (credentials == null) {
                LOG.warn("Login failed: invalid credentials");
                return null;
            }

            // 2. 创建新会话
            PlayerSession newSession = createSession(credentials, game);
            if (newSession == null || newSession.getId() == null) {
                LOG.error("Login failed: session creation returned null or missing ID, credentials={}", credentials);
                return null;
            }

            // 3. 替换旧会话
            PlayerSession oldSession = sessionService.replaceSession(credentials, newSession);
            if (oldSession != null) {
                LOG.warn("Kicked old session: credentials={}, oldSessionId={}, newSessionId={}",
                        credentials,
                        Objects.toString(oldSession.getId(), "N/A"),
                        newSession.getId());
            }

            // 4. 生成 token（委托给 SecurityService）
            String token = securityService.generateToken(credentials);

            LOG.info("Login successful: sessionId={}, credentials={}", newSession.getId(), credentials);
            return new LoginResult(newSession, token);
        } catch (Exception e) {
            LOG.error("Login failed due to exception", e);
            return null;
        }
    }

    /**
     * 验证凭证
     * <p>
     * 从客户端发送的 ByteBuf 中解析并验证凭证。
     *
     * @param buffer 客户端发送的认证数据
     * @return 验证通过返回 Credentials，失败返回 null
     */
    public Credentials verify(ByteBuf buffer) {
        try {
            Credentials credentials = sessionService.verify(buffer);
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
     * 创建新会话
     * <p>
     * 创建新的玩家会话并设置凭证属性。
     * <p>
     * <b>注意：</b>此方法只创建会话，不处理旧会话的替换。
     * 替换逻辑由 {@link SessionService#replaceSession} 处理。
     *
     * @param credentials 凭证
     * @param game        游戏实例
     * @return 新创建的会话
     */
    private PlayerSession createSession(Credentials credentials, Game game) {
        try {
            // 创建新会话
            PlayerSession newSession = game.createPlayerSession();
            newSession.setAttribute("credentials", credentials);

            LOG.info("Session created: credentials={}, sessionId={}", credentials, newSession.getId());
            return newSession;
        } catch (Exception e) {
            LOG.error("Failed to create session for credentials: {}", credentials, e);
            return null;
        }
    }

    /**
     * 获取会话管理器的统计信息
     * <p>
     * 用于监控和调试。
     *
     * @return 缓存统计
     */
    public CacheStats getSessionStats() {
        return sessionService.getStats();
    }

    /**
     * 获取当前会话数量
     *
     * @return 会话总数
     */
    public long getSessionCount() {
        return sessionService.size();
    }
}
