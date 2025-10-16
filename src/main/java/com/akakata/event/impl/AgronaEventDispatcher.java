package com.akakata.event.impl;

import com.akakata.app.Session;
import com.akakata.event.*;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 高性能事件调度器
 * <p>
 * 使用 Agrona 无锁队列实现异步事件分发，提供高吞吐、低延迟的事件处理能力。
 * <p>
 * <b>架构设计：</b>
 * <ul>
 *   <li>事件队列：{@link ManyToOneConcurrentArrayQueue}，容量 32K，无锁设计</li>
 *   <li>处理线程：单独的 {@link AgentRunner} 线程，批量处理事件（256/batch）</li>
 *   <li>空闲策略：{@link BackoffIdleStrategy}，自适应 CPU 占用</li>
 *   <li>Handler 管理：按事件类型分组 + ANY 类型特殊处理</li>
 * </ul>
 * <p>
 * <b>使用场景：</b>
 * <ul>
 *   <li>作为 {@link ShardedEventDispatcher} 的单个分片</li>
 *   <li>作为 Game 的独立 EventDispatcher</li>
 *   <li>需要高性能异步事件处理的场景</li>
 * </ul>
 * <p>
 * <b>性能特点：</b>
 * <ul>
 *   <li>吞吐量：~2M events/sec</li>
 *   <li>延迟：~200μs (平均)</li>
 *   <li>队列容量：32K events</li>
 *   <li>批处理：256 events/batch</li>
 * </ul>
 *
 * @author Kelvin
 */
public class AgronaEventDispatcher implements EventDispatcher, Agent {

    private static final Logger LOG = LoggerFactory.getLogger(AgronaEventDispatcher.class);

    /**
     * 事件队列容量（32K）
     */
    private static final int QUEUE_CAPACITY = 1 << 15;

    /**
     * 每批处理的事件数量
     */
    private static final int BATCH_SIZE = 256;

    // ==================== 字段 ====================

    /**
     * 无锁事件队列
     */
    private final ManyToOneConcurrentArrayQueue<Event> eventQueue;

    /**
     * 按事件类型分组的 handler
     * <p>
     * key: 事件类型，value: 该类型的 handler 列表
     */
    private final Map<Integer, List<EventHandler>> handlersByEventType;

    /**
     * ANY 类型的 handler（接收所有事件）
     */
    private final List<EventHandler> anyHandlers;

    /**
     * 空闲策略（自适应 CPU 占用）
     */
    private final BackoffIdleStrategy idleStrategy;

    /**
     * Agent 运行器（独立线程）
     */
    private AgentRunner agentRunner;

    /**
     * 关闭标志
     */
    private volatile boolean isCloseCalled = false;

    // ==================== 构造函数 ====================

    /**
     * 构造事件调度器
     * <p>
     * 注意：构造后需要调用 {@link #initialize()} 才能开始工作
     */
    public AgronaEventDispatcher() {
        this.eventQueue = new ManyToOneConcurrentArrayQueue<>(QUEUE_CAPACITY);
        this.handlersByEventType = new HashMap<>();
        this.anyHandlers = new CopyOnWriteArrayList<>();
        this.idleStrategy = new BackoffIdleStrategy(
                1, 10,
                TimeUnit.MICROSECONDS.toNanos(1),
                TimeUnit.MILLISECONDS.toNanos(1)
        );
    }

    // ==================== 生命周期 ====================

    /**
     * 初始化并启动调度器
     * <p>
     * 创建独立的 Agent 线程开始处理事件。
     * 必须在使用调度器之前调用此方法。
     */
    public void initialize() {
        if (agentRunner != null) {
            LOG.warn("Dispatcher already initialized");
            return;
        }

        this.agentRunner = new AgentRunner(
                idleStrategy,
                throwable -> LOG.error("Unhandled exception in event dispatcher", throwable),
                null,
                this
        );

        Thread agentThread = new Thread(agentRunner, "agrona-event-dispatcher");
        agentThread.setDaemon(false);
        agentThread.start();

        LOG.info("AgronaEventDispatcher started with queue capacity {} and batch size {}",
                QUEUE_CAPACITY, BATCH_SIZE);
    }

    /**
     * 关闭调度器
     * <p>
     * 停止 Agent 线程，清理所有 handler 和事件队列。
     * 此方法是线程安全的，可重复调用。
     */
    @Override
    public synchronized void close() {
        if (!isCloseCalled) {
            LOG.info("Closing AgronaEventDispatcher");
            if (agentRunner != null) {
                agentRunner.close();
            }
            clear();
            isCloseCalled = true;
        }
    }

    // ==================== Agent 接口实现 ====================

    /**
     * Agent 工作方法（由 AgentRunner 循环调用）
     * <p>
     * 从事件队列批量取出事件并分发给 handler。
     * 每批最多处理 {@link #BATCH_SIZE} 个事件。
     *
     * @return 本次处理的事件数量
     */
    @Override
    public int doWork() {
        int workDone = 0;
        Event event;

        // 批量处理事件，提升吞吐量
        for (int i = 0; i < BATCH_SIZE; i++) {
            event = eventQueue.poll();
            if (event == null) {
                break;
            }

            try {
                dispatchEvent(event);
                workDone++;
            } catch (Exception ex) {
                LOG.error("Error dispatching event type {}", event.getType(), ex);
            }
        }

        return workDone;
    }

    /**
     * Agent 角色名称
     *
     * @return 角色名称
     */
    @Override
    public String roleName() {
        return "event-dispatcher";
    }

    // ==================== EventDispatcher 接口实现 ====================

    /**
     * 触发事件（异步）
     * <p>
     * 将事件放入无锁队列，由 Agent 线程异步处理。
     * 如果队列已满，事件将被丢弃并记录警告日志。
     *
     * @param event 要触发的事件
     */
    @Override
    public void fireEvent(Event event) {
        if (!eventQueue.offer(event)) {
            LOG.warn("Event queue full (capacity {}), dropping event type {}",
                    QUEUE_CAPACITY, event.getType());
        }
    }

    /**
     * 添加事件处理器
     * <p>
     * 根据 handler 的事件类型，注册到对应的分组：
     * <ul>
     *   <li>ANY 类型：注册到 anyHandlers，接收所有事件</li>
     *   <li>特定类型：注册到 handlersByEventType[type]，只接收该类型事件</li>
     * </ul>
     *
     * @param eventHandler 事件处理器
     */
    @Override
    public synchronized void addHandler(EventHandler eventHandler) {
        int eventType = eventHandler.getEventType();
        if (eventType == Events.ANY) {
            addANYHandler(eventHandler);
        } else {
            List<EventHandler> handlers = handlersByEventType.computeIfAbsent(
                    eventType, k -> new CopyOnWriteArrayList<>());
            handlers.add(eventHandler);
            LOG.debug("Added handler for event type {}", eventType);
        }
    }

    /**
     * 获取指定类型的所有 handler
     *
     * @param eventType 事件类型
     * @return handler 列表（副本）
     */
    @Override
    public synchronized List<EventHandler> getHandlers(int eventType) {
        if (eventType == Events.ANY) {
            return new ArrayList<>(anyHandlers);
        }
        List<EventHandler> handlers = handlersByEventType.get(eventType);
        return handlers != null ? new ArrayList<>(handlers) : Collections.emptyList();
    }

    /**
     * 移除事件处理器
     *
     * @param eventHandler 要移除的处理器
     */
    @Override
    public synchronized void removeHandler(EventHandler eventHandler) {
        int eventType = eventHandler.getEventType();
        if (eventType == Events.ANY) {
            anyHandlers.remove(eventHandler);
        } else {
            List<EventHandler> handlers = handlersByEventType.get(eventType);
            if (handlers != null) {
                handlers.remove(eventHandler);
            }
        }
    }

    /**
     * 移除指定事件类型的所有 handler
     *
     * @param eventType 事件类型
     */
    @Override
    public synchronized void removeHandlersForEvent(int eventType) {
        if (eventType == Events.ANY) {
            int count = anyHandlers.size();
            anyHandlers.clear();
            LOG.debug("Removed {} ANY handlers", count);
        } else {
            List<EventHandler> handlers = handlersByEventType.remove(eventType);
            if (handlers != null) {
                LOG.debug("Removed {} handlers for event type {}", handlers.size(), eventType);
            }
        }
    }

    /**
     * 移除指定 session 的所有 handler
     * <p>
     * 遍历所有 handler，找出属于该 session 的 SessionEventHandler 并移除。
     *
     * @param session 会话
     * @return true 如果移除了至少一个 handler
     */
    @Override
    public synchronized boolean removeHandlersForSession(Session session) {
        LOG.debug("Entered removeHandlersForSession for session {}", session);
        List<EventHandler> removeList = new ArrayList<>();

        // 收集所有 handler 列表（包括 anyHandlers 和所有类型的 handlers）
        Collection<List<EventHandler>> eventHandlersList = new ArrayList<>(handlersByEventType.values());
        eventHandlersList.add(anyHandlers);

        // 找出属于该 session 的所有 handler
        for (List<EventHandler> handlerList : eventHandlersList) {
            removeList.addAll(getHandlersToRemoveForSession(handlerList, session));
        }

        LOG.debug("Going to remove {} handlers for session: {}", removeList.size(), session);
        for (EventHandler handler : removeList) {
            removeHandler(handler);
        }
        return removeList.size() > 0;
    }

    /**
     * 清空所有 handler 和事件队列
     */
    @Override
    public synchronized void clear() {
        LOG.trace("Going to clear handlers on dispatcher {}", this);
        if (handlersByEventType != null) {
            handlersByEventType.clear();
        }
        if (anyHandlers != null) {
            anyHandlers.clear();
        }
        eventQueue.clear();
    }

    // ==================== 内部方法 ====================

    /**
     * 分发事件到 handler
     * <p>
     * 分发顺序：
     * <ol>
     *   <li>所有 ANY 类型的 handler（无论事件类型）</li>
     *   <li>该事件类型对应的 handler</li>
     * </ol>
     *
     * @param event 事件
     */
    private void dispatchEvent(Event event) {
        // 1. 分发给所有 ANY handler
        for (EventHandler handler : anyHandlers) {
            try {
                handler.onEvent(event);
            } catch (Exception ex) {
                LOG.error("ANY handler error for event type {}", event.getType(), ex);
            }
        }

        // 2. 分发给特定类型的 handler
        List<EventHandler> handlers = handlersByEventType.get(event.getType());
        if (handlers != null) {
            for (EventHandler handler : handlers) {
                try {
                    handler.onEvent(event);
                } catch (Exception ex) {
                    LOG.error("Handler error for event type {}", event.getType(), ex);
                }
            }
        }
    }

    /**
     * 添加 ANY 类型的 handler
     *
     * @param eventHandler ANY 类型的处理器
     * @throws IllegalArgumentException 如果 handler 不是 ANY 类型
     */
    protected void addANYHandler(EventHandler eventHandler) {
        int eventType = eventHandler.getEventType();
        if (eventType != Events.ANY) {
            LOG.error("The incoming handler {} is not of type ANY", eventHandler);
            throw new IllegalArgumentException("The incoming handler is not of type ANY");
        }
        anyHandlers.add(eventHandler);
        LOG.debug("Added ANY event handler");
    }

    /**
     * 从 handler 列表中找出属于指定 session 的 handler
     *
     * @param handlerList handler 列表
     * @param session     会话
     * @return 属于该 session 的 handler 列表
     */
    protected List<EventHandler> getHandlersToRemoveForSession(List<EventHandler> handlerList, Session session) {
        List<EventHandler> removeList = new ArrayList<>();
        if (handlerList != null) {
            for (EventHandler handler : handlerList) {
                if (handler instanceof SessionEventHandler) {
                    SessionEventHandler sessionHandler = (SessionEventHandler) handler;
                    if (sessionHandler.getSession().equals(session)) {
                        removeList.add(handler);
                    }
                }
            }
        }
        return removeList;
    }

    // ==================== 监控方法 ====================

    /**
     * 获取当前队列中待处理的事件数量
     * <p>
     * 用于监控和调试。
     *
     * @return 队列中的事件数量
     */
    public int getQueueSize() {
        return eventQueue.size();
    }

    /**
     * 获取队列容量
     *
     * @return 队列最大容量
     */
    public int getQueueCapacity() {
        return QUEUE_CAPACITY;
    }
}
