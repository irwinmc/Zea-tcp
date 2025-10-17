package com.akakata.event.impl;

import com.akakata.event.Event;

import java.io.Serializable;

/**
 * 事件的默认实现
 * <p>
 * 表示系统中的一个事件，包含三个核心元素：
 * <ul>
 *   <li><b>事件类型</b> (type)：标识事件的类别，定义在 {@link com.akakata.event.Events} 中</li>
 *   <li><b>事件源</b> (source)：触发事件的对象或数据载荷</li>
 *   <li><b>时间戳</b> (timeStamp)：事件创建或触发的时间（毫秒或纳秒）</li>
 * </ul>
 * <p>
 * <b>设计特点：</b>
 * <ul>
 *   <li>可序列化：实现 {@link Serializable}，支持网络传输和持久化</li>
 *   <li>可扩展：字段使用 protected 修饰，子类可以访问和扩展</li>
 *   <li>轻量级：只包含最基本的事件信息，无额外开销</li>
 * </ul>
 * <p>
 * <b>使用场景：</b>
 * <ul>
 *   <li>通用事件：大多数事件类型的默认实现</li>
 *   <li>工厂方法：通过 {@link com.akakata.event.Events#event(Object, int)} 创建</li>
 *   <li>基类：作为特定事件类型的父类（如 {@link DefaultNetworkEvent}）</li>
 * </ul>
 * <p>
 * <b>使用示例：</b>
 * <pre>{@code
 * // 1. 使用工厂方法创建（推荐）
 * Event event = Events.event(data, Events.SESSION_MESSAGE);
 *
 * // 2. 直接创建
 * DefaultEvent event = new DefaultEvent();
 * event.setType(Events.DISCONNECT);
 * event.setSource(session);
 * event.setTimeStamp(System.currentTimeMillis());
 *
 * // 3. 触发事件
 * session.onEvent(event);
 * }</pre>
 *
 * @author Kelvin
 * @see com.akakata.event.Event
 * @see com.akakata.event.Events
 * @see DefaultNetworkEvent
 */
public class DefaultEvent implements Event, Serializable {

    private static final long serialVersionUID = 4184094945897413320L;

    // ==================== 字段 ====================

    /**
     * 事件类型
     * <p>
     * 标识事件的类别，取值定义在 {@link com.akakata.event.Events} 中。
     * 例如：SESSION_MESSAGE (0x33)、NETWORK_MESSAGE (0x34)、DISCONNECT (0x36) 等。
     */
    protected int type;

    /**
     * 事件源
     * <p>
     * 触发事件的对象或事件携带的数据。
     * 具体含义取决于事件类型：
     * <ul>
     *   <li>SESSION_MESSAGE：通常是消息数据（ByteBuf、String 等）</li>
     *   <li>DISCONNECT：通常是 Session 对象</li>
     *   <li>NETWORK_MESSAGE：通常是要发送的消息</li>
     * </ul>
     */
    protected Object source;

    /**
     * 时间戳
     * <p>
     * 事件创建或触发的时间。
     * <ul>
     *   <li>通常使用 {@code System.currentTimeMillis()}（毫秒精度）</li>
     *   <li>性能分析时可使用 {@code System.nanoTime()}（纳秒精度）</li>
     * </ul>
     */
    protected long timeStamp;

    // ==================== Getter/Setter ====================

    /**
     * 获取事件类型
     *
     * @return 事件类型（定义在 {@link com.akakata.event.Events} 中）
     */
    @Override
    public int getType() {
        return type;
    }

    /**
     * 设置事件类型
     *
     * @param type 事件类型
     */
    @Override
    public void setType(int type) {
        this.type = type;
    }

    /**
     * 获取事件源
     *
     * @return 事件源对象或数据
     */
    @Override
    public Object getSource() {
        return source;
    }

    /**
     * 设置事件源
     *
     * @param source 事件源对象或数据
     */
    @Override
    public void setSource(Object source) {
        this.source = source;
    }

    /**
     * 获取时间戳
     *
     * @return 时间戳（毫秒或纳秒）
     */
    @Override
    public long getTimeStamp() {
        return timeStamp;
    }

    /**
     * 设置时间戳
     *
     * @param timeStamp 时间戳
     */
    @Override
    public void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }

    // ==================== Object 方法 ====================

    /**
     * 返回事件的字符串表示
     * <p>
     * 包含事件的类型、源和时间戳信息，便于调试和日志输出。
     *
     * @return 事件的字符串表示
     */
    @Override
    public String toString() {
        return "Event [type=" + type + ", source=" + source + ", timeStamp=" + timeStamp + "]";
    }
}
