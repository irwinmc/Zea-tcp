package com.akakata.app.impl;

import com.akakata.app.DeliveryReport;
import com.akakata.app.Game;
import com.akakata.app.PlayerSession;
import com.akakata.app.Session;
import com.akakata.event.Event;
import com.akakata.event.NetworkEvent;
import com.akakata.event.impl.EventDispatchers;
import com.akakata.event.impl.NettyEventDispatcher;
import com.akakata.event.impl.SharedNetworkEvent;
import com.akakata.service.IdGeneratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;

/**
 * Provides application-wide session membership and ordered game-event processing.
 *
 * <p>The default server context owns one Game for the application. Its single business
 * executor serializes game events and broadcasts independently of the session shards.
 * Connection takes the session monitor before the game monitor. Shutdown marks
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
    protected final Set<PlayerSession> sessions;

    /**
     * Application-owned dispatcher with one ordered business executor.
     */
    protected final NettyEventDispatcher eventDispatcher;

    /**
     * Flag indicating that resource shutdown has started.
     */
    protected volatile boolean isShuttingDown;

    /**
     * Completion of application membership and dispatcher cleanup.
     */
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();

    /**
     * Initiating cleanup thread, used to avoid reentrant waits.
     */
    private volatile Thread closingThread;

    // ==================== 构造函数 ====================

    /**
     * Creates an application game with concurrent membership and one business executor.
     *
     * @param gameName the diagnostic game name
     */
    protected AbstractGame(String gameName) {
        this(gameName, NettyEventDispatcher.DEFAULT_QUEUE_CAPACITY);
    }

    /**
     * Creates a Game with a configurable bounded business queue.
     *
     * @param gameName      the diagnostic game name
     * @param queueCapacity the pending task capacity, at least 16
     */
    protected AbstractGame(String gameName, int queueCapacity) {
        this(gameName, queueCapacity, NettyEventDispatcher.DEFAULT_QUEUE_BYTES);
    }

    /**
     * Creates a Game with independent count and retained-payload budgets.
     *
     * @param gameName      the application Game name
     * @param queueCapacity maximum pending tasks, at least 16
     * @param queueBytes    positive payload budget including running work
     */
    protected AbstractGame(String gameName, int queueCapacity, long queueBytes) {
        this.id = String.valueOf(ID_GENERATOR.generateFor(AbstractGame.class));
        this.gameName = gameName;
        this.sessions = ConcurrentHashMap.newKeySet();

        this.eventDispatcher = new NettyEventDispatcher(queueCapacity, queueBytes);

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
     * Returns the application dispatcher used to register ordered game-event handlers.
     *
     * <p>This game owns the dispatcher and closes its business executor during shutdown.
     * Player sessions use the separate shared session shards.
     *
     * @return the game's fixed business dispatcher
     * @since 0.8.0
     */
    @Override
    public NettyEventDispatcher getEventDispatcher() {
        return eventDispatcher;
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

        // Remove the session from the application membership set.
        boolean removed = sessions.remove(playerSession);

        if (removed) {
            LOG.debug("PlayerSession {} disconnected from game {}", playerSession.getId(), id);
        }

        return removed;
    }

    // ==================== 事件处理 ====================

    /**
     * Submits an event to the ordered Game queue without waiting for available capacity.
     *
     * @param event the event whose original payload remains owned by the caller
     * @return whether this open Game accepted the event
     */
    @Override
    public boolean send(Event event) {
        return !isShuttingDown && eventDispatcher.fireEvent(event);
    }

    /**
     * Queues a broadcast on the game's fixed executor before targeting player sessions.
     *
     * <p>Each accepted delivery retains its own native payload reference. The caller
     * retains ownership of the original event payload and may release that reference
     * after this method returns. Broadcast actions and game events execute in game
     * submission order; each recipient's outbound event follows its session queue.
     *
     * @param networkEvent the outbound event to broadcast
     * @return completion reporting admission to recipient session queues
     */
    @Override
    public CompletableFuture<DeliveryReport> sendBroadcast(NetworkEvent networkEvent) {
        return queueSend(networkEvent, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<DeliveryReport> sendToPlayer(NetworkEvent networkEvent, PlayerSession recipient) {
        Objects.requireNonNull(recipient, "recipient");
        if (recipient.getGame() != this) {
            throw new IllegalArgumentException("Recipient belongs to another Game");
        }
        return queueSend(networkEvent, List.of(recipient));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<DeliveryReport> sendToPlayers(NetworkEvent networkEvent,
                                                           Collection<? extends PlayerSession> recipients) {
        Set<PlayerSession> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<PlayerSession> selected = new ArrayList<>();
        for (PlayerSession recipient : Objects.requireNonNull(recipients, "recipients")) {
            Objects.requireNonNull(recipient, "recipient");
            if (recipient.getGame() != this) {
                throw new IllegalArgumentException("Recipient belongs to another Game");
            }
            if (seen.add(recipient)) {
                selected.add(recipient);
            }
        }
        return queueSend(networkEvent, List.copyOf(selected));
    }

    /**
     * Publishes a retained recipient action on the same executor as Game events.
     *
     * @param event    the outbound event
     * @param selected the explicit recipients, or null for current Game membership
     * @return completion of recipient admission, including scope cancellation
     */
    private CompletableFuture<DeliveryReport> queueSend(NetworkEvent event, List<PlayerSession> selected) {
        Objects.requireNonNull(event, "event");
        if (isShuttingDown) {
            return CompletableFuture.failedFuture(new RejectedExecutionException("Game is closed"));
        }
        DeliveryReport[] result = new DeliveryReport[1];
        return eventDispatcher.submit(event,
                delivered -> result[0] = selected != null && selected.size() == 1
                        ? deliverToSession((NetworkEvent) delivered, selected.getFirst())
                        : deliverToSessions((NetworkEvent) delivered,
                        selected == null ? sessions : selected)).thenApply(ignored -> result[0]);
    }

    /**
     * Publishes a single recipient without constructing a fan-out encoding lease.
     *
     * <p>The Game delivery retains its source while the Session takes its own
     * admission reference. The Session and socket retain that source independently
     * until their ordered work completes, following the normal event ownership rules.
     *
     * @param networkEvent the borrowed Game-ordered outbound event
     * @param recipient    the selected session instance
     * @return recipient queue admission counts
     */
    private DeliveryReport deliverToSession(NetworkEvent networkEvent, PlayerSession recipient) {
        if (isShuttingDown || recipient.getGame() != this || !recipient.isConnected() || recipient.isShuttingDown()) {
            return new DeliveryReport(0, 0, 1);
        }
        if (recipient.onEvent(networkEvent)) {
            return new DeliveryReport(1, 0, 0);
        }
        try {
            recipient.close();
        } catch (Exception failure) {
            LOG.error("Failed to close overloaded recipient {}", recipient.getId(), failure);
        }
        return new DeliveryReport(0, 1, 0);
    }

    /**
     * Publishes one Game-ordered send, closing recipients that cannot admit an update.
     *
     * @param networkEvent the borrowed outbound event
     * @param recipients   the selected session instances
     * @return recipient queue admission counts
     */
    private DeliveryReport deliverToSessions(NetworkEvent networkEvent, Collection<PlayerSession> recipients) {
        int accepted = 0;
        int rejected = 0;
        int skipped = 0;
        SharedNetworkEvent shared = new SharedNetworkEvent(networkEvent);
        try {
            for (PlayerSession session : recipients) {
                if (isShuttingDown || session.getGame() != this || !session.isConnected() || session.isShuttingDown()) {
                    skipped++;
                } else if (session.onEvent(shared)) {
                    accepted++;
                } else {
                    rejected++;
                    try {
                        session.close();
                    } catch (Exception failure) {
                        LOG.error("Failed to close overloaded recipient {}", session.getId(), failure);
                    }
                }
            }
            return new DeliveryReport(accepted, rejected, skipped);
        } finally {
            shared.releaseOwner();
        }
    }

    // ==================== 生命周期 ====================

    /**
     * Stops accepting sessions, closes connected sessions and releases the game dispatcher.
     *
     * <p>The default implementation continues closing remaining sessions when an
     * individual session fails, and performs cleanup outside the game monitor. External
     * concurrent callers wait for cleanup; reentrant and dispatcher-callback callers
     * avoid waiting on callbacks involved in shutdown.
     */
    @Override
    public void close() {
        List<PlayerSession> sessionsToClose = null;
        synchronized (this) {
            if (!isShuttingDown) {
                closingThread = Thread.currentThread();
                isShuttingDown = true;
                sessionsToClose = new ArrayList<>(sessions);
            }
        }
        if (sessionsToClose == null) {
            if (closingThread != Thread.currentThread() && !NettyEventDispatcher.isDispatchThread()) {
                closeCompletion.join();
                eventDispatcher.close();
            }
            return;
        }

        LOG.info("Closing game: id={}, name={}, sessions={}", id, gameName, sessionsToClose.size());
        try {
            for (PlayerSession session : sessionsToClose) {
                try {
                    session.close();
                } catch (Exception e) {
                    LOG.error("Error closing session {} in game {}", session.getId(), id, e);
                }
            }
        } finally {
            sessions.clear();
            try {
                eventDispatcher.close();
            } finally {
                closeCompletion.complete(null);
                closingThread = null;
            }
        }
        LOG.info("Game closed: id={}, name={}", id, gameName);
    }
}
