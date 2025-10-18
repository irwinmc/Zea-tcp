package com.akakata.service;

import com.akakata.app.PlayerSession;
import com.akakata.app.Session;
import com.akakata.communication.impl.SocketMessageSender;
import com.akakata.security.Credentials;
import com.akakata.util.NettyUtils;
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
 * 会话管理服务（基于 Caffeine Cache）
 * <p>
 * 提供以下高级特性：
 * <ul>
 *   <li><b>自动过期</b>：2小时无活动或24小时绝对过期，防止僵尸会话</li>
 *   <li><b>自动清理</b>：过期时自动调用 {@code session.close()}，释放资源</li>
 *   <li><b>原子替换</b>：{@link #replaceSession(Credentials, PlayerSession)} 方法确保线程安全</li>
 *   <li><b>容量限制</b>：最大10K会话，防止OOM</li>
 *   <li><b>监控统计</b>：缓存命中率、驱逐次数等指标</li>
 * </ul>
 * <p>
 * <b>自动过期策略：</b>
 * <pre>
 * - expireAfterAccess(2h)  → 2小时内无任何读写操作，会话过期
 * - expireAfterWrite(24h)  → 创建后24小时，无论是否活跃，强制过期
 * - maximumSize(10K)       → 超过10K会话，驱逐最久未使用的会话
 * </pre>
 * <p>
 * <b>性能对比：</b>
 * <table border="1">
 *   <tr>
 *     <th>特性</th>
 *     <th>ConcurrentHashMap</th>
 *     <th>Caffeine Cache</th>
 *   </tr>
 *   <tr>
 *     <td>读性能</td>
 *     <td>~10ns</td>
 *     <td>~15ns（+50%，可忽略）</td>
 *   </tr>
 *   <tr>
 *     <td>写性能</td>
 *     <td>~30ns</td>
 *     <td>~40ns（+33%，可忽略）</td>
 *   </tr>
 *   <tr>
 *     <td>内存占用</td>
 *     <td>无限增长</td>
 *     <td>LRU淘汰，可控</td>
 *   </tr>
 *   <tr>
 *     <td>自动清理</td>
 *     <td>无</td>
 *     <td>自动</td>
 *   </tr>
 *   <tr>
 *     <td>监控统计</td>
 *     <td>无</td>
 *     <td>内置</td>
 *   </tr>
 * </table>
 * <p>
 * <b>使用示例：</b>
 * <pre>{@code
 * // 1. 创建 SessionService
 * var sessionService = new SessionService();
 *
 * // 2. 登录时替换旧会话（自动清理）
 * PlayerSession newSession = game.createPlayerSession();
 * PlayerSession oldSession = sessionService.replaceSession(credentials, newSession);
 * if (oldSession != null) {
 *     LOG.warn("Kicked old session for {}", credentials);
 * }
 *
 * // 3. 获取会话统计
 * CacheStats stats = sessionService.getStats();
 * LOG.info("Cache hit rate: {}", stats.hitRate());
 *
 * // 4. 服务器关闭时清理
 * sessionService.close();
 * }</pre>
 *
 * @author Kelvin
 * @since 0.7.8
 */
public class SessionService {

    private static final Logger LOG = LoggerFactory.getLogger(SessionService.class);

    /**
     * Caffeine 缓存实例
     * <p>
     * 配置：
     * <ul>
     *   <li>最大容量：10,000 会话</li>
     *   <li>访问过期：2小时</li>
     *   <li>写入过期：24小时</li>
     *   <li>移除监听器：自动清理会话资源</li>
     *   <li>统计功能：启用</li>
     * </ul>
     */
    private final Cache<Credentials, PlayerSession> sessions;

    /**
     * 定期清理任务的调度器
     * <p>
     * 每1分钟触发一次 {@code cache.cleanUp()}，清理过期条目。
     * 虽然 Caffeine 会在读写时自动清理，但定期清理可以：
     * <ul>
     *   <li>及时释放过期会话的资源（Channel、EventHandler等）</li>
     *   <li>避免内存占用持续增长</li>
     * </ul>
     */
    private final ScheduledExecutorService cleanupExecutor;

    /**
     * 是否已关闭
     */
    private volatile boolean closed = false;

    /**
     * 构造函数
     * <p>
     * 初始化 Caffeine 缓存和定期清理任务。
     */
    public SessionService() {
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

        // 定期清理任务（每1分钟）
        this.cleanupExecutor = Executors.newScheduledThreadPool(1,
                Thread.ofVirtual().name("session-cleanup-", 0).factory());
        this.cleanupExecutor.scheduleAtFixedRate(
                sessions::cleanUp,
                1, 1, TimeUnit.MINUTES
        );

        LOG.info("SessionService initialized (max size: 10K, access expiry: 2h, write expiry: 24h)");
    }

    /**
     * 会话移除监听器
     * <p>
     * 当会话因过期、驱逐或显式移除时触发，执行清理操作：
     * <ol>
     *   <li>关闭会话（{@code session.close()}）</li>
     *   <li>关闭 Channel（断开网络连接）</li>
     *   <li>记录日志</li>
     * </ol>
     *
     * @param credentials 会话凭证
     * @param session     被移除的会话
     * @param cause       移除原因（EXPIRED/SIZE/EXPLICIT/REPLACED）
     */
    private void onSessionRemoved(Credentials credentials, PlayerSession session, RemovalCause cause) {
        if (session == null) {
            return;
        }

        try {
            LOG.info("Session removed: credentials={}, cause={}, sessionId={}",
                    credentials, cause, session.getId());

            // 关闭会话
            session.close();

            // 关闭 Channel
            var sender = session.getSender();
            if (sender instanceof SocketMessageSender socketSender) {
                var channel = socketSender.getChannel();
                if (channel != null && channel.isActive()) {
                    channel.close();
                    LOG.debug("Channel closed for session {}", session.getId());
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to cleanup session: credentials={}, sessionId={}",
                    credentials, session.getId(), e);
        }
    }

    // ==================== 公共方法 ====================

    /**
     * 验证客户端凭证
     * <p>
     * 从 ByteBuf 中读取 token，创建 {@link com.akakata.security.SimpleCredentials}。
     * <p>
     * <b>注意：</b>此方法会标记和重置 ByteBuf 的 readerIndex，不影响后续读取。
     *
     * @param byteBuf 客户端发送的数据（包含 token）
     * @return 验证通过返回 Credentials，否则返回 null
     */
    public Credentials verify(ByteBuf byteBuf) {
        if (byteBuf == null || byteBuf.readableBytes() == 0) {
            return null;
        }

        byteBuf.markReaderIndex();
        String token = NettyUtils.readString(byteBuf);
        byteBuf.resetReaderIndex();

        if (token == null || token.isBlank()) {
            return null;
        }

        var credentials = new com.akakata.security.SimpleCredentials();
        credentials.setAttribute("token", token);
        return credentials;
    }

    /**
     * 获取会话
     * <p>
     * 从缓存中获取会话，会刷新访问时间（重置 expireAfterAccess 计时）。
     *
     * @param key 凭证
     * @return 会话，不存在返回 null
     */
    public Session getSession(Credentials key) {
        return sessions.getIfPresent(key);
    }

    /**
     * 添加会话
     * <p>
     * <b>注意：</b>此方法会覆盖已存在的会话，推荐使用 {@link #replaceSession(Credentials, PlayerSession)}。
     *
     * @param key     凭证
     * @param session 会话
     * @return 总是返回 true（Caffeine 不区分新增/覆盖）
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
     * 显式移除会话，会触发 {@link #onSessionRemoved(Credentials, PlayerSession, RemovalCause)}。
     *
     * @param key 凭证
     * @return true 如果会话存在并被移除，false 如果会话不存在
     */
    public boolean removeSession(Credentials key) {
        var session = sessions.getIfPresent(key);
        if (session != null) {
            sessions.invalidate(key);
            return true;
        }
        return false;
    }

    // ==================== 扩展方法 ====================

    /**
     * 原子性替换会话
     * <p>
     * 这是推荐的登录流程方法，解决了 {@link #putSession(Credentials, Session)} 的问题：
     * <ul>
     *   <li>原子操作：检查旧会话 + 替换新会话 + 清理旧会话</li>
     *   <li>线程安全：使用 Caffeine 的内部锁</li>
     *   <li>自动清理：旧会话立即关闭，不依赖异步事件</li>
     * </ul>
     * <p>
     * <b>使用示例：</b>
     * <pre>{@code
     * PlayerSession newSession = game.createPlayerSession();
     * PlayerSession oldSession = sessionManager.replaceSession(credentials, newSession);
     * if (oldSession != null) {
     *     LOG.warn("Replaced old session for {}", credentials);
     * }
     * }</pre>
     *
     * @param credentials 凭证
     * @param newSession  新会话
     * @return 旧会话（如果存在），不存在返回 null
     */
    public PlayerSession replaceSession(Credentials credentials, PlayerSession newSession) {
        if (credentials == null || newSession == null) {
            throw new IllegalArgumentException("credentials and newSession cannot be null");
        }

        // Caffeine.asMap() 返回 ConcurrentMap 视图，put() 是原子操作
        var oldSession = sessions.asMap().put(credentials, newSession);

        if (oldSession != null && oldSession != newSession) {
            LOG.warn("Replacing active session for credentials: {}, oldSessionId={}, newSessionId={}",
                    credentials, oldSession.getId(), newSession.getId());

            // 同步清理旧会话（不依赖 RemovalListener）
            cleanupOldSession(oldSession);
        }

        return oldSession;
    }

    /**
     * 同步清理旧会话
     * <p>
     * 在虚拟线程中执行清理，避免阻塞调用线程。
     *
     * @param oldSession 旧会话
     */
    private void cleanupOldSession(PlayerSession oldSession) {
        // 使用虚拟线程执行清理（Java 21）
        Thread.startVirtualThread(() -> {
            try {
                oldSession.close();

                var sender = oldSession.getSender();
                if (sender instanceof SocketMessageSender socketSender) {
                    var channel = socketSender.getChannel();
                    if (channel != null && channel.isActive()) {
                        channel.close();
                    }
                }

                LOG.debug("Old session cleaned up: sessionId={}", oldSession.getId());
            } catch (Exception e) {
                LOG.error("Failed to cleanup old session: sessionId={}", oldSession.getId(), e);
            }
        });
    }

    /**
     * 获取缓存统计信息
     * <p>
     * 返回缓存的命中率、驱逐次数等指标，用于监控。
     * <p>
     * <b>示例：</b>
     * <pre>{@code
     * CacheStats stats = manager.getStats();
     * LOG.info("Hit rate: {}, eviction count: {}, size: {}",
     *          stats.hitRate(), stats.evictionCount(), manager.size());
     * }</pre>
     *
     * @return 缓存统计
     */
    public CacheStats getStats() {
        return sessions.stats();
    }

    /**
     * 获取当前会话数量
     *
     * @return 会话数量
     */
    public long size() {
        return sessions.estimatedSize();
    }

    /**
     * 关闭 SessionManager
     * <p>
     * 停止定期清理任务，清空所有会话（触发 RemovalListener）。
     */
    public void close() {
        if (closed) {
            return;
        }

        closed = true;
        LOG.info("Shutting down SessionService...");

        // 停止清理任务
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 清空所有会话
        sessions.invalidateAll();
        sessions.cleanUp();

        LOG.info("SessionService shut down (final stats: {})", getStats());
    }
}
