package com.akakata.event;

import com.akakata.app.Session;

/**
 * 会话事件处理器接口
 * <p>
 * 扩展了 {@link EventHandler}，专门用于处理与 {@link Session} 关联的事件。
 * 每个 SessionEventHandler 都绑定到一个特定的 Session，负责处理该 Session 的所有事件。
 * <p>
 * <b>与 EventHandler 的区别：</b>
 * <ul>
 *   <li><b>EventHandler</b>：通用事件处理器，不关联特定对象</li>
 *   <li><b>SessionEventHandler</b>：绑定到 Session，处理该 Session 的事件，支持基于 Session 的分片路由</li>
 * </ul>
 * <p>
 * <b>分片路由优化：</b>
 * 当使用 {@link com.akakata.event.impl.ShardedEventDispatcher} 时：
 * <ul>
 *   <li>SessionEventHandler 会根据 {@code session.getId().hashCode()} 路由到特定分片</li>
 *   <li>每个 SessionEventHandler 只注册到一个分片（而不是所有分片）</li>
 *   <li>这样可以显著减少 handler 注册数量和事件处理开销</li>
 * </ul>
 * <p>
 * <b>典型使用场景：</b>
 * <ul>
 *   <li>处理玩家的游戏逻辑（移动、攻击、聊天等）</li>
 *   <li>管理玩家状态（登录、登出、断线重连）</li>
 *   <li>处理客户端消息并发送响应</li>
 * </ul>
 * <p>
 * <b>实现类：</b>
 * <ul>
 *   <li>{@link com.akakata.event.impl.DefaultSessionEventHandler}：默认实现，提供常见事件的处理框架</li>
 * </ul>
 * <p>
 * <b>使用示例：</b>
 * <pre>{@code
 * // 1. 创建自定义的 Session 事件处理器
 * public class GameSessionHandler extends DefaultSessionEventHandler {
 *     public GameSessionHandler(Session session) {
 *         super(session);
 *     }
 *
 *     @Override
 *     protected void onDataIn(Event event) {
 *         // 处理客户端消息
 *         ByteBuf data = (ByteBuf) event.getSource();
 *         processClientMessage(data);
 *     }
 *
 *     @Override
 *     public int getEventType() {
 *         return Events.ANY;  // 处理所有类型的事件
 *     }
 * }
 *
 * // 2. 为 Session 添加 Handler
 * PlayerSession session = new DefaultPlayerSession();
 * SessionEventHandler handler = new GameSessionHandler(session);
 * session.addHandler(handler);
 *
 * // 3. 当事件触发时，handler 会自动处理
 * Event event = Events.dataInEvent(data);
 * session.onEvent(event);  // → handler.onEvent(event) → onDataIn(event)
 * }</pre>
 * <p>
 * <b>分片路由示例：</b>
 * <pre>{@code
 * // 假设有 8 个分片，1000 个 PlayerSession
 * ShardedEventDispatcher dispatcher = EventDispatchers.sharedDispatcher();
 *
 * // 每个 Session 的 Handler 只注册到一个分片
 * for (PlayerSession session : sessions) {
 *     SessionEventHandler handler = new GameSessionHandler(session);
 *     dispatcher.addHandler(handler);
 *     // → 根据 session.getId().hashCode() 路由到分片 0-7 中的一个
 * }
 *
 * // 结果：
 * // - 总注册数：1000（而不是 8000）
 * // - 每个分片：~125 个 handler（负载均衡）
 * }</pre>
 *
 * @author Kelvin
 * @see EventHandler
 * @see Session
 * @see com.akakata.event.impl.DefaultSessionEventHandler
 * @see com.akakata.event.impl.ShardedEventDispatcher
 */
public interface SessionEventHandler extends EventHandler {

    /**
     * 获取关联的会话
     * <p>
     * 返回此 Handler 绑定的 Session 对象。
     * {@link com.akakata.event.impl.ShardedEventDispatcher} 使用此方法
     * 获取 Session，并根据 {@code session.getId().hashCode()} 进行分片路由。
     * <p>
     * <b>注意：</b>
     * <ul>
     *   <li>此方法应始终返回同一个 Session 实例（不应返回 null）</li>
     *   <li>返回的 Session 决定了 Handler 被路由到哪个分片</li>
     * </ul>
     *
     * @return 关联的 Session 对象
     */
    Session getSession();

    /**
     * 设置关联的会话
     * <p>
     * 设置此 Handler 绑定的 Session 对象。
     * <p>
     * <b>注意：</b>
     * 大多数实现（如 {@link com.akakata.event.impl.DefaultSessionEventHandler}）
     * 不支持修改 Session，调用此方法会抛出 {@link UnsupportedOperationException}。
     * Session 通常在构造函数中设置，之后不可修改。
     *
     * @param session 要设置的 Session
     * @throws UnsupportedOperationException 如果实现不支持修改 Session
     */
    void setSession(Session session) throws UnsupportedOperationException;
}
