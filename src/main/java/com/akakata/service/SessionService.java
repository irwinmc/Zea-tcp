package com.akakata.service;

import com.akakata.app.PlayerSession;
import com.akakata.app.Session;
import com.akakata.communication.impl.SocketMessageSender;
import com.akakata.event.Events;
import com.akakata.security.Credentials;
import com.akakata.security.CredentialsVerifier;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 会话管理服务
 * <p>
 * <b>核心特性：</b>
 * <ul>
 *   <li><b>自动过期</b>：2小时无活动或24小时绝对过期</li>
 *   <li><b>原子替换</b>：{@link #replaceSession} 确保线程安全</li>
 *   <li><b>智能清理</b>：根据移除原因选择同步/异步清理</li>
 *   <li><b>容量限制</b>：最大10K会话，防止OOM</li>
 *   <li><b>监控统计</b>：内置缓存命中率等指标</li>
 * </ul>
 * <p>
 * <b>使用示例：</b>
 * <pre>{@code
 * // 1. 创建 SessionService
 * SessionService service = new SessionService(
 *     buffer -> new SimpleCredentials(NettyUtils.readString(buffer)),
 *     true  // 启用定期清理
 * );
 *
 * // 2. 登录时替换旧会话
 * PlayerSession newSession = game.createPlayerSession();
 * PlayerSession oldSession = service.replaceSession(credentials, newSession);
 * if (oldSession != null) {
 *     LOG.warn("Kicked old session for {}", credentials);
 * }
 *
 * // 3. 获取统计信息
 * CacheStats stats = service.getStats();
 * LOG.info("Hit rate: {}, Size: {}", stats.hitRate(), service.size());
 * }</pre>
 *
 * @author Kelvin
 * @since 0.7.8
 */
public class SessionService {

    private static final Logger LOG = LoggerFactory.getLogger(SessionService.class);

    /**
     * Caffeine 缓存实例
     */
    private final Cache<Credentials, PlayerSession> sessions;

    /**
     * 凭证验证器
     */
    private final CredentialsVerifier verifier;

    /**
     * 定期清理任务的调度器（可选）
     */
    private final ScheduledExecutorService cleanupExecutor;

    /**
     * 是否已关闭
     */
    private volatile boolean closed = false;

    /**
     * 构造函数（完整配置）
     *
     * @param verifier              凭证验证器
     * @param enablePeriodicCleanup 是否启用定期清理（推荐低流量系统启用）
     */
    public SessionService(CredentialsVerifier verifier, boolean enablePeriodicCleanup) {
        this.verifier = verifier;

        this.sessions = Caffeine.newBuilder()
                // 容量限制：最大10K会话
                .maximumSize(10_000)
                // 访问过期：2小时无活动
                .expireAfterAccess(Duration.ofHours(2))
                // 写入过期：24小时绝对过期
                .expireAfterWrite(Duration.ofHours(24))
                // 移除监听器：自动清理
                .removalListener(this::onSessionRemoved)
                // 启用统计
                .recordStats()
                .build();

        // 可选的定期清理
        if (enablePeriodicCleanup) {
            this.cleanupExecutor = Executors.newScheduledThreadPool(1,
                    Thread.ofVirtual().name("session-cleanup-", 0).factory());
            this.cleanupExecutor.scheduleAtFixedRate(
                    sessions::cleanUp,
                    1, 1, TimeUnit.MINUTES
            );
            LOG.info("SessionService initialized with periodic cleanup enabled");
        } else {
            this.cleanupExecutor = null;
            LOG.info("SessionService initialized (periodic cleanup disabled)");
        }
    }

    /**
     * 简化构造函数（默认启用定期清理）
     *
     * @param verifier 凭证验证器
     */
    public SessionService(CredentialsVerifier verifier) {
        this(verifier, true);
    }

    /**
     * 会话移除监听器（改进版）
     * <p>
     * 根据移除原因选择不同的清理策略：
     * <ul>
     *   <li>EXPIRED/SIZE - 异步清理，不阻塞缓存操作</li>
     *   <li>REPLACED - 不清理，由 {@link #replaceSession} 同步处理</li>
     *   <li>EXPLICIT - 同步清理，立即生效</li>
     * </ul>
     *
     * @param credentials 会话凭证
     * @param session     被移除的会话
     * @param cause       移除原因
     */
    private void onSessionRemoved(Credentials credentials, PlayerSession session, RemovalCause cause) {
        if (session == null) {
            return;
        }

        LOG.info("Session removed: credentials={}, cause={}, sessionId={}",
                credentials, cause, session.getId());

        switch (cause) {
            case EXPIRED, SIZE -> {
                // 过期或容量淘汰：异步清理（不影响缓存性能）
                LOG.debug("Scheduling async cleanup for session: {}", session.getId());
                cleanupAsync(session);
            }
            case REPLACED -> {
                // 避免双重清理问题
                LOG.debug("Session replaced, cleanup handled by replaceSession()");
            }
            case EXPLICIT -> {
                // 手动删除：同步清理
                LOG.debug("Performing sync cleanup for session: {}", session.getId());
                cleanupSync(session);
            }
        }
    }

    /**
     * 同步清理会话
     * <p>
     * 在当前线程中执行清理，阻塞调用者直到完成。
     *
     * @param session 待清理的会话
     */
    private void cleanupSync(PlayerSession session) {
        try {
            // 1. 关闭会话（清理 EventHandler、释放资源等）
            session.close();

            // 2. 关闭 Channel（断开网络连接）
            closeChannelIfActive(session);

            LOG.debug("Session cleaned up synchronously: sessionId={}", session.getId());
        } catch (Exception e) {
            LOG.error("Failed to cleanup session synchronously: sessionId={}", session.getId(), e);
        }
    }

    /**
     * 异步清理会话（使用虚拟线程 - Java 21）
     * <p>
     * 在虚拟线程中执行清理，不阻塞调用者。
     *
     * @param session 待清理的会话
     */
    private void cleanupAsync(PlayerSession session) {
        Thread.startVirtualThread(() -> {
            try {
                session.close();
                closeChannelIfActive(session);
                LOG.debug("Session cleaned up asynchronously: sessionId={}", session.getId());
            } catch (Exception e) {
                LOG.error("Failed to cleanup session asynchronously: sessionId={}", session.getId(), e);
            }
        });
    }

    /**
     * 关闭 Channel（如果活跃）
     *
     * @param session 会话
     */
    private void closeChannelIfActive(PlayerSession session) {
        var sender = session.getSender();
        if (sender instanceof SocketMessageSender socketSender) {
            var channel = socketSender.getChannel();
            if (channel != null && channel.isActive()) {
                channel.close();
                LOG.debug("Channel closed for session {}", session.getId());
            }
        }
    }

    // ==================== 公共方法 ====================

    /**
     * 验证客户端凭证
     * <p>
     * 委托给注入的 {@link CredentialsVerifier}。
     *
     * @param byteBuf 客户端发送的认证数据
     * @return 验证通过返回 Credentials，失败返回 null
     */
    public Credentials verify(ByteBuf byteBuf) {
        try {
            return verifier.verify(byteBuf);
        } catch (Exception e) {
            LOG.error("Failed to verify credentials", e);
            return null;
        }
    }

    /**
     * 获取会话
     * <p>
     * 从缓存中获取会话，会刷新访问时间（重置 expireAfterAccess 计时）。
     *
     * @param key 凭证
     * @return 会话，不存在返回 null
     */
    public PlayerSession getSession(Credentials key) {
        return sessions.getIfPresent(key);
    }

    /**
     * 添加会话
     * <p>
     * <b>注意：</b>此方法会覆盖已存在的会话，推荐使用 {@link #replaceSession}。
     *
     * @param key     凭证
     * @param session 会话
     * @return 总是返回 true
     */
    public boolean putSession(Credentials key, Session session) {
        if (key == null || session == null) {
            return false;
        }
        sessions.put(key, (PlayerSession) session);
        return true;
    }

    /**
     * 移除会话
     * <p>
     * 显式移除会话，会触发 {@link #onSessionRemoved} (cause=EXPLICIT)。
     *
     * @param key 凭证
     * @return true 如果会话存在并被移除
     */
    public boolean removeSession(Credentials key) {
        var session = sessions.getIfPresent(key);
        if (session != null) {
            sessions.invalidate(key);
            return true;
        }
        return false;
    }

    /**
     * ✅ 原子性替换会话（推荐方法）
     * <p>
     * 这是登录流程的推荐方法，解决了多个并发问题：
     * <ul>
     *   <li>✅ 同步踢出旧会话，避免双会话状态</li>
     *   <li>✅ 避免双重清理（RemovalListener 不处理 REPLACED）</li>
     *   <li>✅ 线程安全的原子操作</li>
     * </ul>
     * <p>
     * <b>工作流程：</b>
     * <pre>
     * 1. 获取旧会话
     * 2. 同步发送 LOG_OUT 事件（踢出旧连接）
     * 3. 同步清理旧会话（关闭 Channel 等）
     * 4. 存入新会话（触发 RemovalListener，但不清理）
     * </pre>
     * <p>
     * <b>使用示例：</b>
     * <pre>{@code
     * PlayerSession newSession = game.createPlayerSession();
     * PlayerSession oldSession = sessionService.replaceSession(credentials, newSession);
     * if (oldSession != null) {
     *     LOG.warn("Replaced old session for {}", credentials);
     * }
     * }</pre>
     *
     * @param credentials 凭证
     * @param newSession  新会话
     * @return 旧会话（如果存在），不存在返回 null
     * @throws IllegalArgumentException 如果参数为 null
     */
    public PlayerSession replaceSession(Credentials credentials, PlayerSession newSession) {
        if (credentials == null || newSession == null) {
            throw new IllegalArgumentException("credentials and newSession cannot be null");
        }

        // 1. 获取旧会话
        var oldSession = sessions.getIfPresent(credentials);

        if (oldSession != null) {
            LOG.warn("Replacing active session: credentials={}, oldSessionId={}, newSessionId={}",
                    credentials, oldSession.getId(), newSession.getId());

            // 2. 同步踢出旧会话
            synchronized (oldSession) {
                if (oldSession.getStatus() == Session.Status.CONNECTED) {
                    try {
                        // 发送 LOG_OUT 事件（会触发断开连接）
                        oldSession.onEvent(Events.event(null, Events.LOG_OUT));
                        LOG.debug("Sent LOG_OUT event to old session: {}", oldSession.getId());
                    } catch (Exception e) {
                        LOG.error("Failed to send LOG_OUT to old session: {}", oldSession.getId(), e);
                    }
                }
            }

            // 3. 同步清理旧会话
            cleanupSync(oldSession);
        }

        // 4. 存入新会话
        sessions.put(credentials, newSession);

        return oldSession;
    }

    /**
     * 获取缓存统计信息
     * <p>
     * 返回缓存的命中率、驱逐次数等指标，用于监控。
     *
     * @return 缓存统计
     */
    public CacheStats getStats() {
        return sessions.stats();
    }

    /**
     * 获取当前会话数量
     *
     * @return 会话数量（估算值）
     */
    public long size() {
        return sessions.estimatedSize();
    }

    /**
     * 关闭 SessionService
     * <p>
     * 停止定期清理任务，清空所有会话。
     */
    public void close() {
        if (closed) {
            return;
        }

        closed = true;
        LOG.info("Shutting down SessionService...");

        // 停止清理任务
        if (cleanupExecutor != null) {
            cleanupExecutor.shutdown();
            try {
                if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                cleanupExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // 清空所有会话（触发 RemovalListener）
        sessions.invalidateAll();
        sessions.cleanUp();

        LOG.info("SessionService shut down (final stats: {})", getStats());
    }
}
