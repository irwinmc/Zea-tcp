package com.akakata.event;

import com.akakata.app.Session;

import java.util.List;

/**
 * 事件调度器接口
 * <p>
 * 事件调度器是事件系统的核心组件，负责管理事件处理器（Handler）并分发事件。
 * 它实现了观察者模式，将事件生产者和消费者解耦。
 * <p>
 * <b>核心功能：</b>
 * <ul>
 *   <li><b>Handler 管理</b>：添加、移除、查询事件处理器</li>
 *   <li><b>事件分发</b>：将事件路由到相应的 Handler 进行处理</li>
 *   <li><b>异步处理</b>：支持异步事件分发，避免阻塞调用线程</li>
 *   <li><b>生命周期管理</b>：支持清理和关闭操作</li>
 * </ul>
 * <p>
 * <b>实现类：</b>
 * <ul>
 *   <li>{@link com.akakata.event.impl.AgronaEventDispatcher}：基于 Agrona 的单分片高性能实现（~2M events/sec）</li>
 *   <li>{@link com.akakata.event.impl.ShardedEventDispatcher}：多分片实现，支持负载均衡和并发处理</li>
 * </ul>
 * <p>
 * <b>工作流程：</b>
 * <pre>
 * 1. 注册阶段：
 *    dispatcher.addHandler(handler)
 *    → 根据 handler.getEventType() 分组存储
 *
 * 2. 事件触发：
 *    dispatcher.fireEvent(event)
 *    → 放入无锁队列（异步）
 *    → Agent 线程批量取出（256/batch）
 *    → 分发给匹配类型的所有 Handler
 *
 * 3. Handler 处理：
 *    handler.onEvent(event)
 *    → 执行业务逻辑
 * </pre>
 * <p>
 * <b>使用示例：</b>
 * <pre>{@code
 * // 1. 获取调度器（通常使用共享实例）
 * EventDispatcher dispatcher = EventDispatchers.sharedDispatcher();
 *
 * // 2. 注册 Handler
 * EventHandler handler = new MyEventHandler();
 * dispatcher.addHandler(handler);
 *
 * // 3. 触发事件
 * Event event = Events.event(data, Events.SESSION_MESSAGE);
 * dispatcher.fireEvent(event);  // 异步执行，立即返回
 *
 * // 4. 清理资源（Session 关闭时）
 * dispatcher.removeHandlersForSession(session);
 *
 * // 5. 关闭调度器（服务器关闭时）
 * dispatcher.close();
 * }</pre>
 *
 * @author Kelvin
 * @see Event
 * @see EventHandler
 * @see com.akakata.event.impl.AgronaEventDispatcher
 * @see com.akakata.event.impl.ShardedEventDispatcher
 */
public interface EventDispatcher {

    // ==================== Handler 管理方法 ====================

    /**
     * 添加事件处理器
     * <p>
     * 将 Handler 注册到调度器。调度器会根据 {@link EventHandler#getEventType()}
     * 返回的事件类型，将 Handler 添加到相应的分组中。
     * <p>
     * <b>分组策略：</b>
     * <ul>
     *   <li><b>特定类型</b>：Handler 只接收该类型的事件</li>
     *   <li><b>ANY 类型</b>：Handler 接收所有类型的事件（注册到特殊的 anyHandlers 列表）</li>
     *   <li><b>SessionEventHandler</b>：如果是 {@link SessionEventHandler}，可能会被路由到特定分片</li>
     * </ul>
     * <p>
     * <b>线程安全：</b>此方法是线程安全的，可以在任何线程调用。
     *
     * @param eventHandler 要添加的事件处理器（不能为 null）
     * @throws NullPointerException 如果 eventHandler 为 null
     */
    void addHandler(EventHandler eventHandler);

    /**
     * 获取指定类型的所有处理器
     * <p>
     * 返回所有注册为处理指定事件类型的 Handler 列表。
     * <p>
     * <b>注意：</b>
     * <ul>
     *   <li>返回的列表是副本，修改它不会影响调度器</li>
     *   <li>如果没有 Handler 注册该类型，返回空列表</li>
     *   <li>对于 {@link Events#ANY} 类型，返回 anyHandlers 列表</li>
     * </ul>
     *
     * @param eventType 事件类型（定义在 {@link Events} 中）
     * @return Handler 列表（副本，永不为 null）
     */
    List<EventHandler> getHandlers(int eventType);

    /**
     * 移除事件处理器
     * <p>
     * 从调度器中移除指定的 Handler。
     * 调度器会根据 {@link EventHandler#getEventType()} 找到对应的分组并移除。
     * <p>
     * <b>注意：</b>如果 Handler 未注册，此操作无效果。
     *
     * @param eventHandler 要移除的事件处理器
     */
    void removeHandler(EventHandler eventHandler);

    /**
     * 移除指定事件类型的所有处理器
     * <p>
     * 移除所有注册为处理指定事件类型的 Handler。
     * <p>
     * <b>使用场景：</b>
     * <ul>
     *   <li>清理某个功能模块的所有 Handler</li>
     *   <li>重新配置事件处理逻辑</li>
     * </ul>
     *
     * @param eventType 事件类型（定义在 {@link Events} 中）
     */
    void removeHandlersForEvent(int eventType);

    /**
     * 移除指定会话的所有处理器
     * <p>
     * 遍历所有 Handler，找出属于指定 Session 的 {@link SessionEventHandler} 并移除。
     * <p>
     * <b>使用场景：</b>
     * <ul>
     *   <li>Session 关闭时，清理该 Session 的所有 Handler</li>
     *   <li>玩家下线时，释放相关资源</li>
     * </ul>
     * <p>
     * <b>实现细节：</b>
     * <pre>{@code
     * // 典型实现
     * for (EventHandler handler : allHandlers) {
     *     if (handler instanceof SessionEventHandler) {
     *         SessionEventHandler sessionHandler = (SessionEventHandler) handler;
     *         if (sessionHandler.getSession().equals(session)) {
     *             removeHandler(handler);
     *         }
     *     }
     * }
     * }</pre>
     *
     * @param session 要移除 Handler 的会话
     * @return true 如果移除了至少一个 Handler，false 如果没有找到匹配的 Handler
     */
    boolean removeHandlersForSession(Session session);

    /**
     * 清空所有处理器
     * <p>
     * 移除调度器中的所有 Handler，并清空事件队列。
     * <p>
     * <b>使用场景：</b>
     * <ul>
     *   <li>调度器重置</li>
     *   <li>测试场景下的清理</li>
     * </ul>
     * <p>
     * <b>注意：</b>调用此方法后，队列中未处理的事件会被丢弃。
     */
    void clear();

    // ==================== 事件分发方法 ====================

    /**
     * 触发事件（异步）
     * <p>
     * 将事件放入调度器的队列，由 Agent 线程异步处理。
     * 此方法立即返回，不等待事件处理完成。
     * <p>
     * <b>处理流程：</b>
     * <ol>
     *   <li>事件放入无锁队列（{@link org.agrona.concurrent.ManyToOneConcurrentArrayQueue}）</li>
     *   <li>Agent 线程批量取出事件（256/batch）</li>
     *   <li>根据事件类型找到匹配的 Handler</li>
     *   <li>调用每个 Handler 的 {@link EventHandler#onEvent(Event)} 方法</li>
     * </ol>
     * <p>
     * <b>背压处理：</b>
     * 如果队列已满（默认 32K 容量），事件会被丢弃并记录警告日志。
     * <p>
     * <b>性能特点：</b>
     * <ul>
     *   <li>吞吐量：单个 AgronaEventDispatcher 约 2M events/sec</li>
     *   <li>延迟：平均约 200μs（从 fireEvent 到 handler.onEvent）</li>
     *   <li>无锁：生产者和消费者之间无锁竞争</li>
     * </ul>
     * <p>
     * <b>使用示例：</b>
     * <pre>{@code
     * // 触发事件
     * Event event = Events.event(data, Events.SESSION_MESSAGE);
     * dispatcher.fireEvent(event);  // 立即返回
     *
     * // 事件会异步处理，不阻塞当前线程
     * }</pre>
     *
     * @param event 要触发的事件
     */
    void fireEvent(Event event);

    // ==================== 生命周期方法 ====================

    /**
     * 关闭调度器
     * <p>
     * 停止 Agent 线程，清理所有 Handler 和事件队列。
     * 此方法应在 Session 断开或服务器关闭时调用。
     * <p>
     * <b>执行步骤：</b>
     * <ol>
     *   <li>设置关闭标志，拒绝新事件</li>
     *   <li>停止 Agent 线程（AgentRunner.close()）</li>
     *   <li>清空所有 Handler</li>
     *   <li>清空事件队列</li>
     * </ol>
     * <p>
     * <b>注意：</b>
     * <ul>
     *   <li>此方法是线程安全的，可重复调用</li>
     *   <li>关闭后，队列中未处理的事件会被丢弃</li>
     *   <li>关闭后不应再使用此调度器</li>
     * </ul>
     */
    void close();
}
