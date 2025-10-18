package com.akakata.util;

import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCountUtil;

/**
 * ByteBuf 包装器，支持 try-with-resources 自动释放
 * <p>
 * 这个类解决了 ByteBuf 手动释放容易遗忘的问题，使用 Java 的 try-with-resources
 * 语法自动管理 ByteBuf 的生命周期。
 * <p>
 * <b>使用示例：</b>
 * <pre>{@code
 * // 老旧写法（容易忘记 release）
 * ByteBuf buffer = (ByteBuf) event.getSource();
 * try {
 *     // 使用 buffer...
 * } finally {
 *     buffer.release();  // 容易遗忘
 * }
 *
 * // 现代写法（自动释放）
 * try (var holder = new ByteBufHolder(event.getSource())) {
 *     ByteBuf buffer = holder.buffer();
 *     // 使用 buffer...
 * } // 自动调用 close() → release()
 * }</pre>
 * <p>
 * <b>注意事项：</b>
 * <ul>
 *   <li>source 必须是 ByteBuf 类型，否则抛出 ClassCastException</li>
 *   <li>不要在 try-with-resources 块外部继续使用 buffer（已被释放）</li>
 *   <li>适用于单次使用后立即释放的场景（如 ChannelHandler.channelRead0）</li>
 * </ul>
 *
 * @author Kelvin
 * @since 0.7.8
 */
public class ByteBufHolder implements AutoCloseable {

    private final ByteBuf buffer;

    /**
     * 构造函数
     *
     * @param source 事件源对象（必须是 ByteBuf 类型）
     * @throws ClassCastException 如果 source 不是 ByteBuf
     */
    public ByteBufHolder(Object source) {
        this.buffer = (ByteBuf) source;
    }

    /**
     * 获取包装的 ByteBuf
     *
     * @return ByteBuf 实例
     */
    public ByteBuf buffer() {
        return buffer;
    }

    /**
     * 关闭资源，释放 ByteBuf
     * <p>
     * 此方法由 try-with-resources 自动调用，使用 {@link ReferenceCountUtil#release(Object)}
     * 安全释放 ByteBuf，即使 buffer 为 null 或已释放也不会抛异常。
     */
    @Override
    public void close() {
        ReferenceCountUtil.release(buffer);
    }
}
