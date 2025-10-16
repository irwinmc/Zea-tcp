package com.akakata.app.impl;

import com.akakata.app.Game;
import com.akakata.app.PlayerSession;
import com.akakata.app.Session;
import com.akakata.event.Event;
import com.akakata.event.EventDispatcher;
import com.akakata.event.NetworkEvent;
import com.akakata.event.impl.AgronaEventDispatcher;
import com.akakata.event.impl.EventDispatchers;
import com.akakata.service.UniqueIdGeneratorService;
import com.akakata.service.impl.SimpleUniqueIdGeneratorImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * 抽象游戏房间基类
 * <p>
 * Game 代表一个游戏房间，负责管理房间内的所有 PlayerSession。
 * 每个 Game 有独立的 EventDispatcher，用于处理房间级别的事件。
 * <p>
 * 架构设计：
 * - Game 是容器，不是 Session
 * - Game 使用独立的 AgronaEventDispatcher，不与 PlayerSession 共享
 * - 广播通过直接遍历 sessions 实现，而非通过 EventDispatcher
 *
 * @author Kelvin
 */
public abstract class AbstractGame implements Game {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractGame.class);
    private static final UniqueIdGeneratorService ID_GENERATOR = new SimpleUniqueIdGeneratorImpl();

    // ==================== 字段 ====================

    /**
     * 游戏房间唯一标识
     */
    protected final String id;

    /**
     * 游戏房间名称
     */
    protected final String gameName;

    /**
     * 房间内的所有玩家会话
     */
    protected Set<PlayerSession> sessions;

    /**
     * 房间专用的事件调度器
     * <p>
     * 使用独立的 AgronaEventDispatcher，不与 PlayerSession 的 sharedDispatcher 共享。
     * 用于处理房间级别的事件（如果需要）。
     */
    protected EventDispatcher eventDispatcher;

    /**
     * 房间关闭标志
     * <p>
     * 当房间开始关闭流程时设置为 true，阻止新的玩家连接和事件处理。
     */
    protected volatile boolean isShuttingDown;

    // ==================== 构造函数 ====================

    /**
     * 构造游戏房间
     *
     * @param gameName 房间名称
     */
    protected AbstractGame(String gameName) {
        this.id = String.valueOf(ID_GENERATOR.generateFor(AbstractGame.class));
        this.gameName = gameName;
        this.sessions = new HashSet<>();

        // 创建房间专用的 EventDispatcher
        AgronaEventDispatcher dispatcher = new AgronaEventDispatcher();
        dispatcher.initialize();
        this.eventDispatcher = dispatcher;

        this.isShuttingDown = false;

        LOG.info("Game created: id={}, name={}", id, gameName);
    }

    // ==================== Getters ====================

    /**
     * 获取房间 ID
     *
     * @return 房间 ID
     */
    public String getId() {
        return id;
    }

    /**
     * 获取房间名称
     *
     * @return 房间名称
     */
    public String getGameName() {
        return gameName;
    }

    /**
     * 获取房间内所有玩家会话
     *
     * @return 玩家会话集合
     */
    @Override
    public Set<PlayerSession> getSessions() {
        return sessions;
    }

    /**
     * 检查房间是否正在关闭
     *
     * @return true 如果房间正在关闭
     */
    public boolean isShuttingDown() {
        return isShuttingDown;
    }

    // ==================== 玩家会话管理 ====================

    /**
     * 创建新的玩家会话
     * <p>
     * 直接构造 PlayerSession，不使用工厂模式。
     *
     * @return 新创建的玩家会话
     */
    @Override
    public PlayerSession createPlayerSession() {
        return new DefaultPlayerSession.PlayerSessionBuilder()
                .game(this)
                .build();
    }

    /**
     * 玩家登录回调
     * <p>
     * 在玩家成功登录后调用，用于初始化玩家的事件处理器等。
     * 子类必须实现此方法以定义具体的登录逻辑。
     *
     * @param playerSession 玩家会话
     */
    @Override
    public abstract void onLogin(PlayerSession playerSession);

    /**
     * 连接玩家会话到房间
     * <p>
     * 将玩家会话添加到房间的 sessions 集合中，并设置会话状态。
     * 如果房间正在关闭，则拒绝连接。
     *
     * @param playerSession 玩家会话
     * @return true 如果连接成功，false 如果房间正在关闭
     */
    @Override
    public synchronized boolean connectSession(PlayerSession playerSession) {
        if (isShuttingDown) {
            LOG.warn("Game is shutting down, playerSession {} will not be connected!", playerSession);
            return false;
        }

        // 设置连接状态
        playerSession.setStatus(Session.Status.CONNECTING);
        sessions.add(playerSession);
        playerSession.setGame(this);

        // 设置为已连接
        playerSession.setStatus(Session.Status.CONNECTED);

        LOG.debug("PlayerSession {} connected to game {}", playerSession.getId(), id);

        // 连接后回调
        afterSessionConnect(playerSession);

        return true;
    }

    /**
     * 玩家会话连接后的回调
     * <p>
     * 在玩家会话成功连接到房间后调用。
     * 子类可以覆盖此方法以实现自定义逻辑（如通知其他玩家）。
     *
     * @param playerSession 已连接的玩家会话
     */
    @Override
    public void afterSessionConnect(PlayerSession playerSession) {
        // 默认空实现，子类可覆盖
    }

    /**
     * 断开玩家会话
     * <p>
     * 从房间的 sessions 集合中移除玩家会话，并释放会话的事件处理器。
     *
     * @param playerSession 要断开的玩家会话
     * @return true 如果成功移除会话
     */
    @Override
    public synchronized boolean disconnectSession(PlayerSession playerSession) {
        // 释放玩家会话的事件处理器
        EventDispatchers.release(playerSession.getEventDispatcher(), playerSession);

        // 从房间移除会话
        boolean removed = sessions.remove(playerSession);

        if (removed) {
            LOG.debug("PlayerSession {} disconnected from game {}", playerSession.getId(), id);
        }

        return removed;
    }

    // ==================== 事件处理 ====================

    /**
     * 发送事件到房间的 EventDispatcher
     * <p>
     * 用于处理房间级别的事件（如果需要）。
     * 大多数情况下应该使用 sendBroadcast 来向所有玩家广播。
     *
     * @param event 事件
     */
    @Override
    public void send(Event event) {
        if (!isShuttingDown && eventDispatcher != null) {
            eventDispatcher.fireEvent(event);
        }
    }

    /**
     * 向房间内所有已连接的玩家广播消息
     * <p>
     * 直接遍历 sessions 集合，向每个已连接的玩家发送事件。
     * 不通过 EventDispatcher，避免不必要的开销。
     *
     * @param networkEvent 网络事件
     */
    @Override
    public void sendBroadcast(NetworkEvent networkEvent) {
        if (isShuttingDown) {
            return;
        }

        // 直接遍历所有 PlayerSession 发送，不通过 EventDispatcher
        for (PlayerSession session : sessions) {
            if (session.isConnected()) {
                session.onEvent(networkEvent);
            }
        }
    }

    // ==================== 生命周期 ====================

    /**
     * 关闭房间
     * <p>
     * 设置关闭标志，并关闭房间内所有玩家会话。
     * 此方法是线程安全的。
     */
    @Override
    public synchronized void close() {
        if (isShuttingDown) {
            return;
        }

        LOG.info("Closing game: id={}, name={}, sessions={}", id, gameName, sessions.size());

        isShuttingDown = true;

        // 关闭所有玩家会话
        for (PlayerSession session : sessions) {
            try {
                session.close();
            } catch (Exception e) {
                LOG.error("Error closing session {} in game {}", session.getId(), id, e);
            }
        }

        // 关闭房间的 EventDispatcher
        if (eventDispatcher != null) {
            eventDispatcher.close();
        }

        LOG.info("Game closed: id={}, name={}", id, gameName);
    }
}
