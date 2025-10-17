package com.akakata.event;

/**
 * 事件接口
 * <p>
 * 定义了事件系统中所有事件的基本契约。
 * 事件是系统中状态变化或动作的抽象表示，包含三个核心属性：
 * <ul>
 *   <li><b>类型</b> (type)：标识事件的类别，定义在 {@link Events} 中</li>
 *   <li><b>源</b> (source)：事件的来源对象或携带的数据载荷</li>
 *   <li><b>时间戳</b> (timeStamp)：事件发生的时间点</li>
 * </ul>
 * <p>
 * <b>实现类：</b>
 * <ul>
 *   <li>{@link com.akakata.event.impl.DefaultEvent}：通用事件实现</li>
 *   <li>{@link com.akakata.event.impl.DefaultNetworkEvent}：网络事件实现</li>
 * </ul>
 * <p>
 * <b>事件类型：</b>
 * 所有事件类型定义在 {@link Events} 类中，包括：
 * <ul>
 *   <li>生命周期事件：LOG_IN、LOG_OUT、DISCONNECT</li>
 *   <li>消息事件：SESSION_MESSAGE、NETWORK_MESSAGE</li>
 *   <li>游戏事件：GAME_ENTER、GAME_LEAVE</li>
 *   <li>元数据事件：START、STOP、EXCEPTION</li>
 * </ul>
 * <p>
 * <b>使用示例：</b>
 * <pre>{@code
 * // 创建事件
 * Event event = Events.event(data, Events.SESSION_MESSAGE);
 *
 * // 读取事件信息
 * int type = event.getType();
 * Object data = event.getSource();
 * long time = event.getTimeStamp();
 *
 * // 修改事件（如果需要）
 * event.setSource(newData);
 * event.setTimeStamp(System.currentTimeMillis());
 *
 * // 触发事件
 * session.onEvent(event);
 * }</pre>
 *
 * @author Kelvin
 * @see Events
 * @see com.akakata.event.impl.DefaultEvent
 * @see EventHandler
 */
public interface Event {

    /**
     * 获取事件类型
     * <p>
     * 返回事件的类型标识符，取值定义在 {@link Events} 中。
     * 事件类型决定了如何处理该事件（路由到哪些 handler）。
     * <p>
     * 常用类型示例：
     * <ul>
     *   <li>{@link Events#SESSION_MESSAGE} (0x33)：客户端消息</li>
     *   <li>{@link Events#NETWORK_MESSAGE} (0x34)：服务器消息</li>
     *   <li>{@link Events#DISCONNECT} (0x36)：断开连接</li>
     * </ul>
     *
     * @return 事件类型（byte 值）
     */
    int getType();

    /**
     * 设置事件类型
     * <p>
     * 设置事件的类型标识符。
     * <p>
     * <b>注意：</b>某些事件类型（如 {@link NetworkEvent}）不允许修改类型，
     * 调用此方法会抛出 {@link IllegalArgumentException}。
     *
     * @param type 事件类型（定义在 {@link Events} 中）
     * @throws IllegalArgumentException 如果事件类型不可修改
     */
    void setType(int type);

    /**
     * 获取事件源
     * <p>
     * 返回触发事件的对象或事件携带的数据载荷。
     * 具体含义取决于事件类型：
     * <ul>
     *   <li>SESSION_MESSAGE：通常是 {@link io.netty.buffer.ByteBuf} 或消息对象</li>
     *   <li>NETWORK_MESSAGE：通常是要发送给客户端的数据</li>
     *   <li>DISCONNECT：通常是 {@link com.akakata.app.Session} 对象</li>
     *   <li>EXCEPTION：通常是 {@link Throwable} 异常对象</li>
     * </ul>
     *
     * @return 事件源对象或数据载荷
     */
    Object getSource();

    /**
     * 设置事件源
     * <p>
     * 设置触发事件的对象或事件携带的数据载荷。
     *
     * @param source 事件源对象或数据
     */
    void setSource(Object source);

    /**
     * 获取时间戳
     * <p>
     * 返回事件创建或触发的时间点。
     * <ul>
     *   <li>通常使用 {@code System.currentTimeMillis()}（毫秒精度，用于业务逻辑）</li>
     *   <li>性能分析时使用 {@code System.nanoTime()}（纳秒精度，用于延迟计算）</li>
     * </ul>
     * <p>
     * <b>示例：计算事件处理延迟</b>
     * <pre>{@code
     * // 创建时记录纳秒时间戳
     * event.setTimeStamp(System.nanoTime());
     *
     * // 处理时计算延迟
     * long latencyNanos = System.nanoTime() - event.getTimeStamp();
     * double latencyUs = latencyNanos / 1000.0;  // 转换为微秒
     * }</pre>
     *
     * @return 时间戳（毫秒或纳秒）
     */
    long getTimeStamp();

    /**
     * 设置时间戳
     * <p>
     * 设置事件创建或触发的时间点。
     *
     * @param timeStamp 时间戳（毫秒或纳秒）
     */
    void setTimeStamp(long timeStamp);
}
