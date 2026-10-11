package com.akakata.service;

import com.akakata.app.PlayerSession;
import com.akakata.app.Session;
import com.akakata.app.impl.DefaultSession;
import com.akakata.communication.impl.SocketMessageSender;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns cached player sessions and releases them on replacement, removal, eviction and shutdown.
 *
 * <p>The cache expires entries after two hours of inactivity or 24 hours of age
 * and holds at most 10,000 entries. Cache statistics are recorded. An optional
 * periodic cleanup task drives expiration when traffic is idle.
 *
 * <p>Registration takes the session monitor before the service monitor. Resource
 * cleanup runs outside the service monitor. Identity-based ownership tracks sessions
 * awaiting asynchronous eviction callbacks, and close listeners remove default
 * sessions without deleting newer replacements for the same credential key.
 *
 * @author Kelvin
 * @since 0.7.8
 */
public class SessionService {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(SessionService.class);

    /**
     * Credential-keyed session cache with expiration, capacity and removal notifications.
     */
    private final Cache<Credentials, PlayerSession> sessions;

    /**
     * Credential verifier supplied by the application.
     */
    private final CredentialsVerifier verifier;

    /**
     * Optional scheduler that drives cache expiration during idle periods.
     */
    private final ScheduledExecutorService cleanupExecutor;

    /**
     * Terminal lifecycle flag, published when shutdown begins.
     */
    private volatile boolean closed = false;

    // Include removed sessions whose asynchronous Caffeine cleanup has not completed yet.
    // Guarded by this, with identity semantics matching each resource's lifecycle.

    /**
     * Identity-based ownership set including entries awaiting asynchronous removal cleanup; guarded
     * by this service.
     */
    private final Set<PlayerSession> ownedSessions = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Creates the session cache with optional periodic cleanup. The one-argument overload enables
     * cleanup.
     *
     * @param verifier the credential verifier
     * @param enablePeriodicCleanup whether to schedule cache cleanup every minute
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
     * Creates the session cache with optional periodic cleanup. The one-argument overload enables
     * cleanup.
     *
     * @param verifier the credential verifier
     */
    public SessionService(CredentialsVerifier verifier) {
        this(verifier, true);
    }

    /**
     * Handles cache eviction notifications. Expiration and size eviction clean up on the callback
     * thread; replacement cleanup is handled by registration.
     *
     * @param credentials the verified credential identity
     * @param session the session associated with the operation
     * @param cause the failure or cache-removal cause
     */
    private void onSessionRemoved(Credentials credentials, PlayerSession session, RemovalCause cause) {
        if (session == null || session.isShuttingDown()) {
            return;
        }

        LOG.info("Session removed: credentials={}, cause={}, sessionId={}",
                credentials, cause, session.getId());

        switch (cause) {
            case EXPIRED, SIZE -> cleanupSync(session);
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
     * Closes a session, closes an active socket transport as a fallback and removes its ownership
     * record. Cleanup failures are logged.
     *
     * @param session the session associated with the operation
     */
    private void cleanupSync(PlayerSession session) {
        var sender = session.getSender();
        try {
            // 1. 关闭会话（清理 EventHandler、释放资源等）
            session.close();

            LOG.debug("Session cleaned up synchronously: sessionId={}", session.getId());
        } catch (Exception e) {
            LOG.error("Failed to cleanup session synchronously: sessionId={}", session.getId(), e);
        } finally {
            // Keep the original sender: close() may detach it even when cleanup throws.
            if (sender instanceof SocketMessageSender socketSender) {
                var channel = socketSender.getChannel();
                if (channel != null && channel.isActive()) {
                    channel.close();
                }
            }
            synchronized (this) {
                ownedSessions.remove(session);
            }
        }
    }

    /**
     * Removes a closed session from ownership and deletes its cache entry only if it still refers
     * to that instance.
     *
     * @param credentials the verified credential identity
     * @param session the session associated with the operation
     */
    private void onSessionClosed(Credentials credentials, PlayerSession session) {
        synchronized (this) {
            // A delayed close from an old login must never remove its replacement.
            sessions.asMap().computeIfPresent(credentials, (key, current) -> current == session ? null : current);
            ownedSessions.remove(session);
        }
    }

    // ==================== 公共方法 ====================

    /**
     * Invokes the configured credential verifier without taking ownership of the login buffer.
     *
     * @param byteBuf the borrowed login payload buffer
     * @return the verifier's credential result
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
     * Looks up a player session and updates the cache access time.
     *
     * @param key the attribute or cache key
     * @return the cached session, or {@code null} if absent
     */
    public PlayerSession getSession(Credentials key) {
        return sessions.getIfPresent(key);
    }

    /**
     * Registers a player session and closes any distinct previous session for the key.
     *
     * <p>Invalid arguments, nonplayer sessions and closed resources are rejected.
     *
     * @param key the attribute or cache key
     * @param session the session associated with the operation
     * @return {@code true} if registration succeeds; {@code false} if the input or lifecycle state
     * is invalid
     */
    public boolean putSession(Credentials key, Session session) {
        if (key == null || !(session instanceof PlayerSession playerSession)) {
            return false;
        }
        try {
            registerSession(key, playerSession);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * Removes a cached entry and synchronously releases its session.
     *
     * <p>The overload with an expected session removes the entry only if its current
     * value is that exact instance, preventing stale callbacks from deleting replacements.
     *
     * @param key the attribute or cache key
     * @return {@code true} if an entry was removed
     */
    public boolean removeSession(Credentials key) {
        PlayerSession removed;
        synchronized (this) {
            removed = sessions.asMap().remove(key);
        }
        if (removed == null) {
            return false;
        }
        cleanupSync(removed);
        return true;
    }

    /**
     * Removes a cached entry and synchronously releases its session.
     *
     * <p>The overload with an expected session removes the entry only if its current
     * value is that exact instance, preventing stale callbacks from deleting replacements.
     *
     * @param key the attribute or cache key
     * @param expected the exact session instance required for removal
     * @return {@code true} if an entry was removed
     */
    public boolean removeSession(Credentials key, PlayerSession expected) {
        boolean removed;
        synchronized (this) {
            var current = sessions.asMap().get(key);
            removed = current != null && current == expected;
            if (removed) {
                sessions.asMap().remove(key);
            }
        }
        if (removed) {
            cleanupSync(expected);
        }
        return removed;
    }

    /**
     * Publishes a replacement session and closes the previous distinct session outside the registry
     * monitor.
     *
     * <p>Replacing a session with the same instance does not close it.
     *
     * @param credentials the verified credential identity
     * @param newSession the replacement player session
     * @return the previous session, or {@code null} if no entry existed
     * @throws IllegalArgumentException if the credentials or replacement is null
     * @throws IllegalStateException if the service or replacement session is closed
     */
    public PlayerSession replaceSession(Credentials credentials, PlayerSession newSession) {
        if (credentials == null || newSession == null) {
            throw new IllegalArgumentException("credentials and newSession cannot be null");
        }
        return registerSession(credentials, newSession);
    }

    /**
     * Registers ownership and a close listener atomically with respect to session closure, then
     * cleans up the old entry.
     *
     * @param credentials the verified credential identity
     * @param newSession the replacement player session
     * @return the previous session, or {@code null} if no entry existed
     * @throws IllegalStateException if the service or new session is closed
     */
    private PlayerSession registerSession(Credentials credentials, PlayerSession newSession) {
        PlayerSession oldSession;
        // Share DefaultSession.close()'s monitor to register the close listener atomically.
        // Lock order is session -> service. Cleanup always runs outside the service monitor.
        synchronized (newSession) {
            if (newSession.isShuttingDown() || newSession.getStatus() == Session.Status.CLOSED) {
                throw new IllegalStateException("Session is closed");
            }
            synchronized (this) {
                if (closed) {
                    throw new IllegalStateException("SessionService is closed");
                }
                oldSession = sessions.asMap().put(credentials, newSession);
                ownedSessions.add(newSession);
                if (oldSession != newSession && newSession instanceof DefaultSession defaultSession) {
                    defaultSession.addCloseListener(() -> onSessionClosed(credentials, newSession));
                }
            }
        }
        if (oldSession != null && oldSession != newSession) {
            cleanupSync(oldSession);
        }
        return oldSession;
    }

    /**
     * Returns recorded cache statistics.
     *
     * @return the cumulative cache statistics snapshot
     */
    public CacheStats getStats() {
        return sessions.stats();
    }

    /**
     * Returns the estimated number of cached sessions.
     *
     * @return the estimated cache size
     */
    public long size() {
        return sessions.estimatedSize();
    }

    /**
     * Stops periodic cleanup and closes every owned session before invalidating the cache.
     *
     * <p>Session cleanup runs outside the service monitor. Repeated calls have no effect
     * after the service is marked closed, and one failed session does not skip the rest.
     */
    public void close() {
        ArrayList<PlayerSession> sessionsToClose;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            sessionsToClose = new ArrayList<>(ownedSessions);
        }

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

        // RemovalListener runs asynchronously, so close the snapshot on this thread first.
        for (PlayerSession session : sessionsToClose) {
            cleanupSync(session);
        }

        // Already-closed sessions are ignored by the removal listener.
        sessions.invalidateAll();
        sessions.cleanUp();

        LOG.info("SessionService shut down (final stats: {})", getStats());
    }
}
