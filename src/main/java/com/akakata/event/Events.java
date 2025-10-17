package com.akakata.event;

import com.akakata.event.impl.DefaultEvent;
import com.akakata.event.impl.DefaultNetworkEvent;

/**
 * 事件类型常量和工厂方法
 * <p>
 * 定义了事件系统中所有事件类型的常量，并提供便捷的工厂方法来创建事件。
 * 这是事件系统的中心枢纽，所有事件类型都在此定义。
 * <p>
 * <b>事件类型分类：</b>
 * <ul>
 *   <li><b>特殊类型</b>：{@link #ANY} - Handler 可以使用此类型接收所有事件</li>
 *   <li><b>生命周期事件</b>：LOG_IN、LOG_OUT 及其成功/失败变体</li>
 *   <li><b>游戏事件</b>：GAME_ENTER、GAME_LEAVE 及其成功/失败变体</li>
 *   <li><b>消息事件</b>：SESSION_MESSAGE（客户端→服务器）、NETWORK_MESSAGE（服务器→客户端）</li>
 *   <li><b>控制事件</b>：START、STOP、DISCONNECT、EXCEPTION 等</li>
 * </ul>
 * <p>
 * <b>设计原则：</b>
 * <ul>
 *   <li>使用 byte 类型节省内存（相比 int）</li>
 *   <li>使用十六进制值便于识别和调试</li>
 *   <li>相关事件使用相近的十六进制值（如 0x11-0x19 为登录相关）</li>
 *   <li>提供工厂方法而不是直接 new，便于统一管理</li>
 * </ul>
 * <p>
 * <b>使用示例：</b>
 * <pre>{@code
 * // 1. 创建通用事件
 * Event event = Events.event(data, Events.SESSION_MESSAGE);
 *
 * // 2. 创建网络事件（推荐使用工厂方法）
 * NetworkEvent networkEvent = Events.networkEvent(messageData);
 *
 * // 3. 创建客户端数据事件
 * Event dataEvent = Events.dataInEvent(byteBuf);
 *
 * // 4. 在 Handler 中使用事件类型
 * @Override
 * public int getEventType() {
 *     return Events.SESSION_MESSAGE;  // 只处理客户端消息
 * }
 *
 * // 5. 在 Handler 中判断事件类型
 * if (event.getType() == Events.DISCONNECT) {
 *     // 处理断开连接
 * }
 * }</pre>
 *
 * @author Kelvin
 * @see Event
 * @see NetworkEvent
 * @see EventHandler
 */
public class Events {

    // ==================== 特殊类型 ====================

    /**
     * ANY 类型（0x00）
     * <p>
     * 特殊的事件类型，表示"任意类型"。
     * <p>
     * <b>重要：</b>
     * <ul>
     *   <li>事件本身不应该使用这个类型（Event.getType() 不应返回 ANY）</li>
     *   <li>只有 EventHandler 可以使用这个类型，表示接收所有事件</li>
     *   <li>返回 ANY 的 Handler 会被注册到所有分片（如果使用 ShardedEventDispatcher）</li>
     * </ul>
     * <p>
     * <b>使用场景：</b>
     * <ul>
     *   <li>日志记录：监听所有事件并记录</li>
     *   <li>监控统计：统计所有事件的数量和延迟</li>
     *   <li>调试工具：在开发时打印所有事件</li>
     * </ul>
     */
    public final static byte ANY = 0x00;

    // ==================== 生命周期事件 (0x11-0x19) ====================

    /**
     * 登录事件 (0x11)
     * <p>
     * 表示玩家开始登录流程。
     */
    public final static byte LOG_IN = 0x11;

    /**
     * 登录成功事件 (0x14)
     * <p>
     * 表示玩家登录成功，可以发送成功响应给客户端。
     */
    public final static byte LOG_IN_SUCCESS = 0x14;

    /**
     * 登录失败事件 (0x15)
     * <p>
     * 表示玩家登录失败（密码错误、账号不存在等），应发送错误响应。
     */
    public final static byte LOG_IN_FAILURE = 0x15;

    /**
     * 登出事件 (0x17)
     * <p>
     * 表示玩家主动登出。
     */
    public final static byte LOG_OUT = 0x17;

    /**
     * 登出成功事件 (0x18)
     * <p>
     * 表示玩家登出成功，资源已清理。
     */
    public final static byte LOG_OUT_SUCCESS = 0x18;

    /**
     * 登出失败事件 (0x19)
     * <p>
     * 表示玩家登出失败（少见）。
     */
    public final static byte LOG_OUT_FAILURE = 0x19;

    // ==================== 游戏事件 (0x2a-0x2e) ====================

    /**
     * 进入游戏事件 (0x2a)
     * <p>
     * 表示玩家请求进入游戏房间。
     */
    public final static byte GAME_ENTER = 0x2a;

    /**
     * 进入游戏成功事件 (0x2c)
     * <p>
     * 表示玩家成功进入游戏房间。
     */
    public final static byte GAME_ENTER_SUCCESS = 0x2c;

    /**
     * 进入游戏失败事件 (0x2d)
     * <p>
     * 表示玩家进入游戏房间失败（房间满、权限不足等）。
     */
    public final static byte GAME_ENTER_FAILURE = 0x2d;

    /**
     * 离开游戏事件 (0x2e)
     * <p>
     * 表示玩家离开游戏房间。
     */
    public final static byte GAME_LEAVE = 0x2e;

    // ==================== 元数据和控制事件 (0x30-0x40) ====================

    /**
     * 协议版本事件 (0x30)
     * <p>
     * 用于协商客户端和服务器的协议版本。
     */
    public final static byte PROTOCOL_VERSION = 0x30;

    /**
     * 启动事件 (0x31)
     * <p>
     * 表示某个组件或服务开始运行。
     */
    public final static byte START = 0x31;

    /**
     * 停止事件 (0x32)
     * <p>
     * 表示某个组件或服务停止运行。
     */
    public final static byte STOP = 0x32;

    /**
     * 会话消息事件 (0x33)
     * <p>
     * 客户端发送到服务器的消息。
     * <p>
     * <b>典型流程：</b>
     * <pre>
     * 客户端发送消息
     *   → Netty Handler 接收
     *   → Events.dataInEvent(byteBuf)
     *   → session.onEvent(event)
     *   → DefaultSessionEventHandler.onDataIn()
     *   → 业务逻辑处理
     * </pre>
     */
    public final static byte SESSION_MESSAGE = 0x33;

    /**
     * 网络消息事件 (0x34)
     * <p>
     * 服务器发送到客户端的消息。
     * <p>
     * <b>典型流程：</b>
     * <pre>
     * Events.networkEvent(data)
     *   → session.onEvent(networkEvent)
     *   → DefaultSessionEventHandler.onNetworkMessage()
     *   → MessageSender.sendMessage()
     *   → 发送到客户端
     * </pre>
     */
    public final static byte NETWORK_MESSAGE = 0x34;

    /**
     * 断开连接事件 (0x36)
     * <p>
     * 表示客户端连接断开（主动或被动）。
     * <p>
     * <b>触发场景：</b>
     * <ul>
     *   <li>客户端主动断开</li>
     *   <li>网络超时</li>
     *   <li>服务器主动踢出</li>
     *   <li>异常导致的连接中断</li>
     * </ul>
     */
    public final static byte DISCONNECT = 0x36;

    /**
     * 异常事件 (0x40)
     * <p>
     * 表示发生了异常或错误。
     * <p>
     * <b>注意：</b>event.getSource() 通常是 {@link Throwable} 对象。
     */
    public final static byte EXCEPTION = 0x40;

    // ==================== 工厂方法 ====================

    /**
     * 创建事件
     * <p>
     * 通用的事件工厂方法，创建 {@link DefaultEvent} 实例。
     * <p>
     * <b>注意：</b>
     * <ul>
     *   <li>时间戳自动设置为 {@code System.currentTimeMillis()}（毫秒精度）</li>
     *   <li>如果需要纳秒精度（用于性能分析），需要手动调用 {@code event.setTimeStamp(System.nanoTime())}</li>
     * </ul>
     *
     * @param source    事件源或数据载荷
     * @param eventType 事件类型（使用本类定义的常量）
     * @return 创建的事件实例
     */
    public static Event event(Object source, int eventType) {
        DefaultEvent event = new DefaultEvent();
        event.setSource(source);
        event.setType(eventType);
        event.setTimeStamp(System.currentTimeMillis());
        return event;
    }

    /**
     * 创建网络事件
     * <p>
     * 创建 {@link NetworkEvent} 实例，事件类型固定为 {@link #NETWORK_MESSAGE}。
     * <p>
     * <b>使用场景：</b>
     * <ul>
     *   <li>服务器向客户端发送消息</li>
     *   <li>广播消息给所有玩家</li>
     * </ul>
     * <p>
     * <b>示例：</b>
     * <pre>{@code
     * // 创建网络事件
     * NetworkEvent event = Events.networkEvent(messageData);
     *
     * // 发送给客户端
     * session.onEvent(event);
     *
     * // 或广播给所有玩家
     * game.sendBroadcast(event);
     * }</pre>
     *
     * @param source 要发送的消息数据
     * @return 创建的网络事件实例
     */
    public static NetworkEvent networkEvent(Object source) {
        Event event = event(source, Events.NETWORK_MESSAGE);
        NetworkEvent networkEvent = new DefaultNetworkEvent(event);
        return networkEvent;
    }

    /**
     * 创建客户端数据事件
     * <p>
     * 创建 {@link Event} 实例，事件类型固定为 {@link #SESSION_MESSAGE}。
     * <p>
     * <b>使用场景：</b>
     * <ul>
     *   <li>Netty Handler 接收到客户端消息后创建事件</li>
     *   <li>包装客户端发送的数据</li>
     * </ul>
     * <p>
     * <b>示例：</b>
     * <pre>{@code
     * // 在 Netty Handler 中
     * @Override
     * protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
     *     Event event = Events.dataInEvent(msg);
     *     session.onEvent(event);
     * }
     * }</pre>
     *
     * @param source 客户端发送的数据（通常是 {@link io.netty.buffer.ByteBuf}）
     * @return 创建的事件实例
     */
    public static Event dataInEvent(Object source) {
        return event(source, Events.SESSION_MESSAGE);
    }
}
