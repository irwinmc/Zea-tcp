package com.akakata.event;

/**
 * 事件处理器接口
 * <p>
 * 定义了事件处理器的基本契约。事件处理器负责处理特定类型的事件，
 * 并在事件发生时执行相应的业务逻辑。
 * <p>
 * <b>工作原理：</b>
 * <ol>
 *   <li>Handler 注册到 {@link EventDispatcher}：{@code dispatcher.addHandler(handler)}</li>
 *   <li>EventDispatcher 根据 {@link #getEventType()} 将 Handler 分组</li>
 *   <li>当事件触发时，Dispatcher 调用匹配类型的所有 Handler 的 {@link #onEvent(Event)}</li>
 *   <li>Handler 在 {@code onEvent()} 中执行业务逻辑</li>
 * </ol>
 * <p>
 * <b>事件类型：</b>
 * <ul>
 *   <li><b>特定类型</b>：返回具体的事件类型（如 {@link Events#SESSION_MESSAGE}），只处理该类型事件</li>
 *   <li><b>ANY 类型</b>：返回 {@link Events#ANY}，接收所有类型的事件</li>
 * </ul>
 * <p>
 * <b>实现类：</b>
 * <ul>
 *   <li>{@link com.akakata.event.impl.DefaultSessionEventHandler}：Session 事件处理器基类</li>
 *   <li>{@link SessionEventHandler}：关联 Session 的事件处理器接口</li>
 * </ul>
 * <p>
 * <b>使用示例：</b>
 * <pre>{@code
 * // 1. 实现 EventHandler（处理特定类型）
 * EventHandler handler = new EventHandler() {
 *     @Override
 *     public void onEvent(Event event) {
 *         // 处理 SESSION_MESSAGE 事件
 *         ByteBuf data = (ByteBuf) event.getSource();
 *         // ... 业务逻辑
 *     }
 *
 *     @Override
 *     public int getEventType() {
 *         return Events.SESSION_MESSAGE;  // 只接收 SESSION_MESSAGE
 *     }
 * };
 *
 * // 2. 注册 Handler
 * dispatcher.addHandler(handler);
 *
 * // 3. 触发事件后，handler.onEvent() 会被自动调用
 * Event event = Events.dataInEvent(data);
 * dispatcher.fireEvent(event);  // → 调用 handler.onEvent(event)
 * }</pre>
 * <p>
 * <b>ANY 类型 Handler 示例：</b>
 * <pre>{@code
 * // 监听所有事件的 Handler（用于日志、监控等）
 * EventHandler anyHandler = new EventHandler() {
 *     @Override
 *     public void onEvent(Event event) {
 *         // 接收所有类型的事件
 *         log.debug("Event: type={}, source={}", event.getType(), event.getSource());
 *     }
 *
 *     @Override
 *     public int getEventType() {
 *         return Events.ANY;  // 接收所有事件
 *     }
 * };
 * }</pre>
 *
 * @author Kelvin
 * @see Event
 * @see EventDispatcher
 * @see SessionEventHandler
 * @see Events
 */
public interface EventHandler {

    /**
     * 处理事件
     * <p>
     * 当符合类型的事件被触发时，{@link EventDispatcher} 会调用此方法。
     * 实现类应在此方法中执行事件处理逻辑。
     * <p>
     * <b>注意事项：</b>
     * <ul>
     *   <li>此方法在 EventDispatcher 的 Agent 线程中执行</li>
     *   <li>不要阻塞此方法（避免影响其他事件处理）</li>
     *   <li>如果处理失败，抛出异常会被 EventDispatcher 捕获并记录日志</li>
     *   <li>对于耗时操作，考虑异步处理或使用独立线程池</li>
     * </ul>
     * <p>
     * <b>典型实现：</b>
     * <pre>{@code
     * @Override
     * public void onEvent(Event event) {
     *     try {
     *         // 1. 提取事件数据
     *         Object data = event.getSource();
     *
     *         // 2. 执行业务逻辑
     *         processData(data);
     *
     *         // 3. 如果需要，触发新事件
     *         Event responseEvent = Events.networkEvent(response);
     *         session.onEvent(responseEvent);
     *     } catch (Exception e) {
     *         log.error("Failed to handle event", e);
     *         // 异常会被 EventDispatcher 捕获，不会影响其他 handler
     *     }
     * }
     * }</pre>
     *
     * @param event 要处理的事件
     */
    void onEvent(Event event);

    /**
     * 获取事件类型
     * <p>
     * 返回此 Handler 关心的事件类型。{@link EventDispatcher} 使用此值
     * 决定哪些事件应该路由到这个 Handler。
     * <p>
     * <b>返回值：</b>
     * <ul>
     *   <li><b>特定类型</b>：如 {@link Events#SESSION_MESSAGE}，只接收该类型事件</li>
     *   <li><b>{@link Events#ANY}</b>：接收所有类型的事件（用于日志、监控等）</li>
     * </ul>
     * <p>
     * <b>注意：</b>
     * <ul>
     *   <li>此方法应返回常量值，不应动态变化</li>
     *   <li>返回 ANY 会导致 Handler 被注册到所有分片（如果使用 ShardedEventDispatcher）</li>
     *   <li>大多数业务 Handler 应返回特定类型，而不是 ANY</li>
     * </ul>
     * <p>
     * <b>示例：</b>
     * <pre>{@code
     * // 只处理客户端消息
     * @Override
     * public int getEventType() {
     *     return Events.SESSION_MESSAGE;
     * }
     *
     * // 处理所有事件（慎用）
     * @Override
     * public int getEventType() {
     *     return Events.ANY;
     * }
     * }</pre>
     *
     * @return 事件类型（定义在 {@link Events} 中）
     */
    int getEventType();
}
