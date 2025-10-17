package com.akakata.event.impl;

import com.akakata.event.Event;
import com.akakata.event.Events;
import com.akakata.event.NetworkEvent;
import io.netty.channel.Channel;

/**
 * 网络事件的默认实现
 * <p>
 * 表示需要通过网络发送到远程客户端的事件。
 * 继承自 {@link DefaultEvent}，并固定事件类型为 {@link Events#NETWORK_MESSAGE}。
 * <p>
 * <b>设计特点：</b>
 * <ul>
 *   <li>类型固定：事件类型永远是 NETWORK_MESSAGE (0x34)，不可修改</li>
 *   <li>携带 Channel：可选地携带 Netty Channel 引用，用于直接发送</li>
 *   <li>拷贝构造：支持从其他 Event 复制数据，统一转换为 NetworkEvent</li>
 * </ul>
 * <p>
 * <b>使用场景：</b>
 * <ul>
 *   <li>服务器向客户端发送消息（游戏状态更新、聊天消息等）</li>
 *   <li>广播事件：通过 {@code Game.sendBroadcast()} 向所有玩家发送</li>
 *   <li>事件转换：将 SESSION_MESSAGE 转换为 NETWORK_MESSAGE 后发送</li>
 * </ul>
 * <p>
 * <b>使用示例：</b>
 * <pre>{@code
 * // 1. 使用工厂方法创建（推荐）
 * NetworkEvent event = Events.networkEvent(messageData);
 *
 * // 2. 直接创建
 * NetworkEvent event = new DefaultNetworkEvent();
 * event.setSource(messageData);
 *
 * // 3. 从其他事件复制（常用于转换）
 * Event sessionEvent = Events.dataInEvent(data);
 * NetworkEvent networkEvent = new DefaultNetworkEvent(sessionEvent);
 *
 * // 4. 发送给客户端
 * session.onEvent(networkEvent);  // → MessageSender.sendMessage()
 *
 * // 5. 广播给所有玩家
 * game.sendBroadcast(networkEvent);
 * }</pre>
 * <p>
 * <b>事件流转：</b>
 * <pre>
 * SESSION_MESSAGE (客户端数据) → DefaultSessionEventHandler.onDataIn()
 *   → 转换为 NetworkEvent
 *   → Game.sendBroadcast()
 *   → 所有 PlayerSession.onEvent()
 *   → DefaultSessionEventHandler.onNetworkMessage()
 *   → MessageSender.sendMessage()
 *   → 发送到客户端
 * </pre>
 *
 * @author Kyia
 * @see NetworkEvent
 * @see DefaultEvent
 * @see Events#networkEvent(Object)
 * @see com.akakata.app.Game#sendBroadcast(NetworkEvent)
 */
public class DefaultNetworkEvent extends DefaultEvent implements NetworkEvent {

    private static final long serialVersionUID = -4779264464285216014L;

    // ==================== 字段 ====================

    /**
     * Netty Channel 引用（可选）
     * <p>
     * 如果设置了 Channel，可以直接通过这个 Channel 发送消息。
     * 大多数情况下此字段为 null，使用 Session 中的 MessageSender 发送。
     */
    private Channel channel;

    // ==================== 构造函数 ====================

    /**
     * 默认构造函数
     * <p>
     * 创建一个空的 NetworkEvent，事件类型自动设置为 {@link Events#NETWORK_MESSAGE}。
     * 需要手动设置 source（消息数据）和 timeStamp。
     */
    public DefaultNetworkEvent() {
        super.setType(Events.NETWORK_MESSAGE);
    }

    /**
     * 拷贝构造函数
     * <p>
     * 从其他 Event 复制 source 和 timeStamp，创建一个 NetworkEvent。
     * 事件类型会被覆盖为 {@link Events#NETWORK_MESSAGE}（忽略原始事件的类型）。
     * <p>
     * <b>使用场景：</b>
     * <pre>{@code
     * // 将 SESSION_MESSAGE 转换为 NETWORK_MESSAGE
     * Event sessionEvent = Events.dataInEvent(data);
     * NetworkEvent networkEvent = new DefaultNetworkEvent(sessionEvent);
     * game.sendBroadcast(networkEvent);
     * }</pre>
     *
     * @param event 源事件（类型会被忽略）
     */
    public DefaultNetworkEvent(Event event) {
        this(event, null);
    }

    /**
     * 拷贝构造函数（带 Channel）
     * <p>
     * 从其他 Event 复制 source 和 timeStamp，并设置 Netty Channel。
     * 事件类型会被覆盖为 {@link Events#NETWORK_MESSAGE}。
     * <p>
     * <b>使用场景：</b>
     * <ul>
     *   <li>需要直接通过特定 Channel 发送消息</li>
     *   <li>在 Netty Handler 中创建响应事件</li>
     * </ul>
     *
     * @param event   源事件（类型会被忽略）
     * @param channel Netty Channel（可以为 null）
     */
    public DefaultNetworkEvent(Event event, Channel channel) {
        this.setSource(event.getSource());
        this.setTimeStamp(event.getTimeStamp());
        this.channel = channel;
        super.setType(Events.NETWORK_MESSAGE);
    }

    // ==================== Getter/Setter ====================

    /**
     * 获取 Netty Channel
     *
     * @return Netty Channel（可能为 null）
     */
    public Channel getChannel() {
        return channel;
    }

    /**
     * 设置 Netty Channel
     *
     * @param channel Netty Channel
     */
    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    // ==================== 重写方法 ====================

    /**
     * 禁止修改事件类型
     * <p>
     * NetworkEvent 的类型永远是 {@link Events#NETWORK_MESSAGE}，不允许修改。
     *
     * @param type 事件类型（会被忽略）
     * @throws IllegalArgumentException 总是抛出此异常
     */
    @Override
    public void setType(int type) {
        throw new IllegalArgumentException(
                "Event type of this class is already set to NETWORK_MESSAGE. It should not be reset.");
    }
}
