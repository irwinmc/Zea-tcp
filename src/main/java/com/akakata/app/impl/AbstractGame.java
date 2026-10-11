package com.akakata.app.impl;

import com.akakata.app.Game;
import com.akakata.app.PlayerSession;
import com.akakata.app.Session;
import com.akakata.event.Event;
import com.akakata.event.EventDispatcher;
import com.akakata.event.NetworkEvent;
import com.akakata.event.impl.AgronaEventDispatcher;
import com.akakata.event.impl.EventDispatchers;
import com.akakata.service.IdGeneratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implements concurrent session membership and a dedicated dispatcher for game events.
 *
 * <p>Connection takes the session monitor before the game monitor. Shutdown marks
 * the game closed under its monitor and releases sessions outside that monitor.
 * Subclasses provide application behavior in {@link #onLogin(com.akakata.app.PlayerSession)}.
 *
 * @author Kelvin
 */
public abstract class AbstractGame implements Game {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AbstractGame.class);
    /**
     * Identifier generator used for new game instances.
     */
    private static final IdGeneratorService ID_GENERATOR = new IdGeneratorService();

    // ==================== 字段 ====================

    /**
     * Identifier used for game identity.
     */
    protected final String id;

    /**
     * Diagnostic game name.
     */
    protected final String gameName;

    /**
     * Live set of sessions associated with the game.
     */
    protected Set<PlayerSession> sessions;

    /**
     * Dispatcher used for event delivery; ownership depends on whether it is shared.
     */
    protected EventDispatcher eventDispatcher;

    /**
     * Flag indicating that resource shutdown has started.
     */
    protected volatile boolean isShuttingDown;

    // ==================== 构造函数 ====================

    /**
     * Creates a game with an empty concurrent session set and starts its dedicated dispatcher.
     *
     * @param gameName the diagnostic game name
     */
    protected AbstractGame(String gameName) {
        this.id = String.valueOf(ID_GENERATOR.generateFor(AbstractGame.class));
        this.gameName = gameName;
        this.sessions = ConcurrentHashMap.newKeySet();

        // 创建房间专用的 EventDispatcher
        AgronaEventDispatcher dispatcher = new AgronaEventDispatcher();
        dispatcher.initialize();
        this.eventDispatcher = dispatcher;

        this.isShuttingDown = false;

        LOG.info("Game created: id={}, name={}", id, gameName);
    }

    // ==================== Getters ====================

    /**
     * Returns the identifier.
     *
     * @return the identifier
     */
    public String getId() {
        return id;
    }

    /**
     * Returns the diagnostic game name.
     *
     * @return the diagnostic game name
     */
    public String getGameName() {
        return gameName;
    }

    /**
     * Returns the game's live session membership set.
     *
     * <p>Use lifecycle methods to coordinate membership changes with session state.
     *
     * @return the live set of player sessions
     */
    @Override
    public Set<PlayerSession> getSessions() {
        return sessions;
    }

    /**
     * Tests whether game closure has started.
     *
     * @return {@code true} once the game is marked closed
     */
    public boolean isShuttingDown() {
        return isShuttingDown;
    }

    // ==================== 玩家会话管理 ====================

    /**
     * Creates a player session associated with this game without connecting it.
     *
     * @return the newly created player session
     * @throws IllegalStateException if the default game implementation is closed
     */
    @Override
    public synchronized PlayerSession createPlayerSession() {
        if (isShuttingDown) {
            throw new IllegalStateException("Game is closed");
        }
        return new DefaultPlayerSession.PlayerSessionBuilder()
                .game(this)
                .build();
    }

    /**
     * Installs application event handlers after successful authentication and protocol setup.
     *
     * @param playerSession the player session
     */
    @Override
    public abstract void onLogin(PlayerSession playerSession);

    /**
     * Connects a session to the game if both are open and it is not already connected.
     *
     * <p>The default implementation takes the session monitor before the game monitor.
     * The connection callback runs after its own synchronized blocks exit; a caller may
     * still hold the session monitor. A failing callback closes the session.
     *
     * @param playerSession the player session
     * @return {@code true} if accepted; {@code false} if closed or already connected
     */
    @Override
    public boolean connectSession(PlayerSession playerSession) {
        // Share DefaultSession.close()'s monitor so connecting and closing cannot overlap.
        synchronized (playerSession) {
            synchronized (this) {
                if (isShuttingDown || playerSession.isShuttingDown()
                        || playerSession.getStatus() == Session.Status.CLOSED
                        || playerSession.isConnected() || sessions.contains(playerSession)) {
                    return false;
                }
                playerSession.setStatus(Session.Status.CONNECTING);
                sessions.add(playerSession);
                playerSession.setGame(this);
                playerSession.setStatus(Session.Status.CONNECTED);
            }
        }

        // Application callbacks run outside both lifecycle monitors.
        LOG.debug("PlayerSession {} connected to game {}", playerSession.getId(), id);
        try {
            afterSessionConnect(playerSession);
            return true;
        } catch (RuntimeException e) {
            try {
                playerSession.close();
            } catch (Exception closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    /**
     * Runs the application callback after a session joins the game. The default implementation does
     * nothing.
     *
     * @param playerSession the player session
     */
    @Override
    public void afterSessionConnect(PlayerSession playerSession) {
        // 默认空实现，子类可覆盖
    }

    /**
     * Removes a session from this game and releases its event registrations.
     *
     * <p>This operation does not itself close the transport. Use the session's
     * {@code close()} method for complete session cleanup.
     *
     * @param playerSession the player session
     * @return {@code true} if the session was present and removed
     */
    @Override
    public boolean disconnectSession(PlayerSession playerSession) {
        // 释放玩家会话的事件处理器
        if (!playerSession.isShuttingDown()) {
            EventDispatchers.release(playerSession.getEventDispatcher(), playerSession);
        }

        // 从房间移除会话
        boolean removed = sessions.remove(playerSession);

        if (removed) {
            LOG.debug("PlayerSession {} disconnected from game {}", playerSession.getId(), id);
        }

        return removed;
    }

    // ==================== 事件处理 ====================

    /**
     * Submits an event to the game dispatcher. A closed default game ignores the event.
     *
     * @param event the event to process
     */
    @Override
    public void send(Event event) {
        if (!isShuttingDown && eventDispatcher != null) {
            eventDispatcher.fireEvent(event);
        }
    }

    /**
     * Submits the outbound event to each connected session.
     *
     * <p>Each accepted delivery retains its own native payload reference. The caller
     * retains ownership of the original event payload and may release that reference
     * after this method returns.
     *
     * @param networkEvent the outbound event to broadcast
     */
    @Override
    public void sendBroadcast(NetworkEvent networkEvent) {
        if (isShuttingDown) {
            return;
        }

        // 遍历所有 PlayerSession
        for (PlayerSession session : sessions) {
            if (session.isConnected()) {
                session.onEvent(networkEvent);
            }
        }
    }

    // ==================== 生命周期 ====================

    /**
     * Stops accepting sessions, closes connected sessions and releases the game dispatcher.
     *
     * <p>The default implementation continues closing remaining sessions when an
     * individual session fails, and performs cleanup outside the game monitor.
     */
    @Override
    public void close() {
        List<PlayerSession> sessionsToClose;
        synchronized (this) {
            if (isShuttingDown) {
                return;
            }
            LOG.info("Closing game: id={}, name={}, sessions={}", id, gameName, sessions.size());
            isShuttingDown = true;
            sessionsToClose = new ArrayList<>(sessions);
        }

        // 关闭所有玩家会话
        for (PlayerSession session : sessionsToClose) {
            try {
                session.close();
            } catch (Exception e) {
                LOG.error("Error closing session {} in game {}", session.getId(), id, e);
            }
        }
        sessions.clear();

        // 关闭房间的 EventDispatcher
        if (eventDispatcher != null) {
            eventDispatcher.close();
        }

        LOG.info("Game closed: id={}, name={}", id, gameName);
    }
}
