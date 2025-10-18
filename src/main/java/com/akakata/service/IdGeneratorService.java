package com.akakata.service;

import com.akakata.util.ZealConfig;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ID 生成服务
 * <p>
 * 用于生成全局唯一的 ID，支持以下特性：
 * <ul>
 *   <li>原子递增：使用 {@link AtomicLong} 保证线程安全</li>
 *   <li>节点前缀：可选的节点名称前缀（用于分布式环境）</li>
 *   <li>类型前缀：为特定类型生成带前缀的 ID</li>
 * </ul>
 * <p>
 * <b>使用场景：</b>
 * <ul>
 *   <li>Session ID 生成</li>
 *   <li>Event ID 生成</li>
 *   <li>任何需要全局唯一 ID 的场景</li>
 * </ul>
 * <p>
 * <b>使用示例：</b>
 * <pre>{@code
 * var idGenerator = new IdGeneratorService();
 *
 * // 1. 生成普通 ID（纯数字或带节点前缀）
 * Object id1 = idGenerator.generate();  // → 1 或 "node1-1"
 *
 * // 2. 生成带类型前缀的 ID
 * Object id2 = idGenerator.generateFor(PlayerSession.class);  // → "PlayerSession-2"
 * }</pre>
 * <p>
 * <b>节点配置：</b>
 * <pre>
 * 设置系统属性：-D{@link ZealConfig#NODE_NAME}=node1
 * 生成的 ID 格式：node1-1, node1-2, ...
 * </pre>
 *
 * @author Kelvin
 */
public class IdGeneratorService {

    /**
     * 全局 ID 计数器（原子递增）
     */
    private static final AtomicLong ID = new AtomicLong(0L);

    /**
     * 生成全局唯一 ID
     * <p>
     * ID 格式取决于是否配置了节点名称：
     * <ul>
     *   <li>无节点名称：纯数字 ID（1, 2, 3, ...）</li>
     *   <li>有节点名称：带节点前缀（node1-1, node1-2, ...）</li>
     * </ul>
     *
     * @return 唯一 ID（Long 或 String）
     */
    public Object generate() {
        String nodeName = System.getProperty(ZealConfig.NODE_NAME);
        if (null == nodeName || nodeName.isEmpty()) {
            return ID.incrementAndGet();
        } else {
            return nodeName + "-" + ID.incrementAndGet();
        }
    }

    /**
     * 为特定类型生成带前缀的唯一 ID
     * <p>
     * ID 格式：{@code ClassName-ID}
     * <p>
     * 例如：
     * <ul>
     *   <li>{@code generateFor(PlayerSession.class)} → "PlayerSession-1"</li>
     *   <li>{@code generateFor(Event.class)} → "Event-2"</li>
     * </ul>
     *
     * @param klass 类型（用于生成前缀）
     * @return 带类型前缀的唯一 ID
     */
    public Object generateFor(Class<?> klass) {
        return klass.getSimpleName() + "-" + ID.incrementAndGet();
    }

    /**
     * 重置 ID 计数器（仅用于测试）
     * <p>
     * <b>警告：</b>此方法会破坏 ID 的唯一性，仅应在单元测试中使用。
     */
    public void resetForTesting() {
        ID.set(0L);
    }
}
