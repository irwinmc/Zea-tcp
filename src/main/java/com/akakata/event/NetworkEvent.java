package com.akakata.event;

/**
 * 网络事件接口
 * <p>
 * 标记接口，表示需要通过网络发送到远程客户端的事件。
 * 继承自 {@link Event}，添加了网络传输的语义。
 * <p>
 * <b>设计目的：</b>
 * <ul>
 *   <li>类型区分：明确标识这是一个网络事件，而不是普通事件</li>
 *   <li>语义明确：表示事件的目标是发送给客户端</li>
 *   <li>类型安全：可以在方法参数中明确要求 NetworkEvent 类型</li>
 * </ul>
 * <p>
 * <b>实现类：</b>
 * <ul>
 *   <li>{@link com.akakata.event.impl.DefaultNetworkEvent}：默认实现，类型固定为 {@link Events#NETWORK_MESSAGE}</li>
 * </ul>
 * <p>
 * <b>与普通 Event 的区别：</b>
 * <table border="1">
 *   <tr>
 *     <th>特性</th>
 *     <th>Event</th>
 *     <th>NetworkEvent</th>
 *   </tr>
 *   <tr>
 *     <td>用途</td>
 *     <td>通用事件（内部流转）</td>
 *     <td>网络事件（发送给客户端）</td>
 *   </tr>
 *   <tr>
 *     <td>事件类型</td>
 *     <td>可变（SESSION_MESSAGE、DISCONNECT 等）</td>
 *     <td>固定为 NETWORK_MESSAGE (0x34)</td>
 *   </tr>
 *   <tr>
 *     <td>处理方式</td>
 *     <td>EventDispatcher 分发</td>
 *     <td>MessageSender 发送</td>
 *   </tr>
 *   <tr>
 *     <td>典型 source</td>
 *     <td>ByteBuf、Session、Throwable</td>
 *     <td>要发送的消息数据</td>
 *   </tr>
 * </table>
 * <p>
 * <b>事件流转：</b>
 * <pre>
 * 客户端消息 → SESSION_MESSAGE (Event)
 *   ↓
 * DefaultSessionEventHandler.onDataIn()
 *   ↓
 * 转换为 NetworkEvent
 *   ↓
 * Game.sendBroadcast(NetworkEvent)
 *   ↓
 * 所有 PlayerSession.onEvent(NetworkEvent)
 *   ↓
 * DefaultSessionEventHandler.onNetworkMessage()
 *   ↓
 * MessageSender.sendMessage()
 *   ↓
 * 发送到客户端
 * </pre>
 * <p>
 * <b>使用示例：</b>
 * <pre>{@code
 * // 1. 创建网络事件（使用工厂方法）
 * NetworkEvent event = Events.networkEvent(messageData);
 *
 * // 2. 或从其他事件转换
 * Event sessionEvent = Events.dataInEvent(data);
 * NetworkEvent networkEvent = new DefaultNetworkEvent(sessionEvent);
 *
 * // 3. 发送给单个客户端
 * session.onEvent(networkEvent);
 *
 * // 4. 广播给所有客户端
 * game.sendBroadcast(networkEvent);
 *
 * // 5. Handler 处理
 * @Override
 * protected void onNetworkMessage(NetworkEvent event) {
 *     MessageSender sender = session.getSender();
 *     sender.sendMessage(event);  // 发送到客户端
 * }
 * }</pre>
 * <p>
 * <b>最佳实践：</b>
 * <ul>
 *   <li>使用 {@link Events#networkEvent(Object)} 工厂方法创建，而不是直接 new</li>
 *   <li>在 {@link com.akakata.app.Game#sendBroadcast(NetworkEvent)} 等方法中使用 NetworkEvent 类型参数，确保类型安全</li>
 *   <li>不要尝试修改 NetworkEvent 的事件类型（会抛异常）</li>
 * </ul>
 *
 * @author Kelvin
 * @see Event
 * @see com.akakata.event.impl.DefaultNetworkEvent
 * @see Events#networkEvent(Object)
 * @see Events#NETWORK_MESSAGE
 */
public interface NetworkEvent extends Event {

    // 标记接口，无额外方法
    // 所有方法继承自 Event 接口

}
