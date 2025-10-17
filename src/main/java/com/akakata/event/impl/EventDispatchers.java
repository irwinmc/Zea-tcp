package com.akakata.event.impl;

import com.akakata.app.Session;
import com.akakata.event.EventDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 事件调度器工厂类
 * <p>
 * 提供全局共享的 {@link ShardedEventDispatcher} 实例，以及相关的工厂方法和管理功能。
 * 这是事件系统的核心入口，负责创建和管理事件调度器。
 * <p>
 * <b>架构设计：</b>
 * <ul>
 *   <li>进程级单例：整个 JVM 共享一个 ShardedEventDispatcher 实例</li>
 *   <li>自动分片：根据 CPU 核心数自动计算分片数量（最小 2，向上取 2 的幂）</li>
 *   <li>懒加载：在类加载时初始化共享调度器</li>
 *   <li>线程安全：所有方法都是线程安全的</li>
 * </ul>
 * <p>
 * <b>使用场景：</b>
 * <ul>
 *   <li><b>共享调度器（推荐）</b>：大多数 Session 使用 {@link #sharedDispatcher()}，共享分片资源</li>
 *   <li><b>专用调度器</b>：特殊场景需要隔离时使用 {@link #newDedicatedDispatcher()}</li>
 *   <li><b>监控</b>：通过 {@link #sharedQueueSize()} 等方法监控队列状态</li>
 * </ul>
 * <p>
 * <b>分片数量计算：</b>
 * <pre>
 * 分片数 = max(2, 2^⌊log2(CPU核心数)⌋)
 *
 * 示例：
 * - 4 核 CPU → 4 分片
 * - 8 核 CPU → 8 分片
 * - 12 核 CPU → 8 分片（向下取 2 的幂）
 * - 16 核 CPU → 16 分片
 * </pre>
 * <p>
 * <b>使用示例：</b>
 * <pre>{@code
 * // 1. 默认使用共享调度器（推荐）
 * EventDispatcher dispatcher = EventDispatchers.sharedDispatcher();
 *
 * // 2. PlayerSession 获取专属分片（最佳实践）
 * PlayerSession session = new DefaultPlayerSession();
 * EventDispatcher shard = EventDispatchers.getShardForSession(session);
 * session.setEventDispatcher(shard);
 *
 * // 3. Session 关闭时释放资源
 * EventDispatchers.release(dispatcher, session);
 *
 * // 4. 服务器关闭时清理共享调度器
 * EventDispatchers.shutdownSharedDispatcher();
 *
 * // 5. 监控队列状态
 * int total = EventDispatchers.sharedQueueSize();
 * int[] perShard = EventDispatchers.sharedShardQueueSizes();
 * }</pre>
 *
 * @author Kelvin
 */
public final class EventDispatchers {

    private static final Logger LOG = LoggerFactory.getLogger(EventDispatchers.class);

    // ==================== 静态字段 ====================

    /**
     * 进程级共享的 ShardedEventDispatcher
     * <p>
     * 在类加载时初始化，分片数根据 CPU 核心数自动计算。
     * 整个 JVM 生命周期内只有一个实例。
     */
    private static final ShardedEventDispatcher SHARED_DISPATCHER = createSharedDispatcher();

    /**
     * 共享调度器关闭状态标志
     * <p>
     * 使用 AtomicBoolean 确保 {@link #shutdownSharedDispatcher()} 只能成功执行一次。
     */
    private static final AtomicBoolean SHARED_CLOSED = new AtomicBoolean(false);

    // ==================== 构造函数 ====================

    /**
     * 私有构造函数，禁止实例化
     * <p>
     * EventDispatchers 是工厂类，只提供静态方法。
     */
    private EventDispatchers() {
    }

    // ==================== 初始化方法 ====================

    /**
     * 创建共享的 ShardedEventDispatcher
     * <p>
     * 分片数计算规则：
     * <ul>
     *   <li>获取 CPU 核心数：{@code Runtime.getRuntime().availableProcessors()}</li>
     *   <li>向下取最接近的 2 的幂：{@code Integer.highestOneBit(cores)}</li>
     *   <li>最小值为 2：{@code Math.max(2, ...)}，确保至少有 2 个分片</li>
     * </ul>
     *
     * @return 初始化好的 ShardedEventDispatcher
     */
    private static ShardedEventDispatcher createSharedDispatcher() {
        int cores = Runtime.getRuntime().availableProcessors();
        int shardCount = Math.max(2, Integer.highestOneBit(cores));

        ShardedEventDispatcher dispatcher = new ShardedEventDispatcher(shardCount);

        LOG.info("Initialized shared ShardedEventDispatcher with {} shards (CPU cores: {})",
                dispatcher.shardCount(), cores);

        return dispatcher;
    }

    // ==================== 获取调度器方法 ====================

    /**
     * 获取共享的事件调度器
     * <p>
     * 返回进程级单例的 {@link ShardedEventDispatcher}。
     * 大多数 Session 应该使用这个共享调度器，以充分利用分片资源。
     * <p>
     * <b>注意：</b>
     * <ul>
     *   <li>这是一个 ShardedEventDispatcher，内部有多个 AgronaEventDispatcher 分片</li>
     *   <li>直接使用此调度器会触发分片路由（基于事件类型）</li>
     *   <li>推荐使用 {@link #getShardForSession(Session)} 获取专属分片，避免路由开销</li>
     * </ul>
     *
     * @return 共享的 ShardedEventDispatcher 实例
     */
    public static EventDispatcher sharedDispatcher() {
        return SHARED_DISPATCHER;
    }

    /**
     * 获取属于某个 session 的专属分片
     * <p>
     * 从共享的 ShardedEventDispatcher 中获取该 session 对应的分片。
     * 这样 session 可以直接持有它的分片引用，避免每次 fireEvent 都要路由。
     * <p>
     * <b>使用场景：</b>
     * <pre>{@code
     * // PlayerSession 构造时获取专属分片
     * PlayerSession session = new DefaultPlayerSession();
     * EventDispatcher shard = EventDispatchers.getShardForSession(session);
     * session.setEventDispatcher(shard);  // 直接持有分片引用
     *
     * // 之后 session.onEvent(event) 直接到达分片，无路由开销
     * }</pre>
     * <p>
     * <b>优势：</b>
     * <ul>
     *   <li>避免路由开销：不需要每次 fireEvent 都计算 hash</li>
     *   <li>负载均衡：基于 session.getId().hashCode() 均匀分布</li>
     *   <li>隔离性：同一 session 的所有事件都在同一分片处理，无竞争</li>
     * </ul>
     *
     * @param session 会话
     * @return 该 session 对应的 EventDispatcher 分片（AgronaEventDispatcher 实例）
     */
    public static EventDispatcher getShardForSession(Session session) {
        return SHARED_DISPATCHER.getShardForSession(session);
    }

    /**
     * 创建专用的事件调度器
     * <p>
     * 创建一个独立的 ShardedEventDispatcher（2 分片），不与共享调度器共享资源。
     * <p>
     * <b>使用场景：</b>
     * <ul>
     *   <li>需要完全隔离的事件处理（如管理控制台、监控系统）</li>
     *   <li>需要独立的线程池和队列</li>
     *   <li>临时使用，可随时关闭（不影响共享调度器）</li>
     * </ul>
     * <p>
     * <b>注意：</b>
     * <ul>
     *   <li>每个专用调度器会创建独立的 Agent 线程（2 个）</li>
     *   <li>使用完毕后必须调用 {@link #release(EventDispatcher, Session)} 或 {@code dispatcher.close()}</li>
     *   <li>大多数情况下推荐使用共享调度器，而不是专用调度器</li>
     * </ul>
     *
     * @return 新创建的专用 ShardedEventDispatcher（2 分片）
     */
    public static EventDispatcher newDedicatedDispatcher() {
        return new ShardedEventDispatcher(2);
    }

    // ==================== 资源管理方法 ====================

    /**
     * 释放调度器资源
     * <p>
     * 根据调度器类型执行不同的清理策略：
     * <ul>
     *   <li><b>共享调度器</b>：只移除该 session 的所有 handler（调度器继续运行）</li>
     *   <li><b>专用调度器</b>：完全关闭调度器，停止所有 Agent 线程</li>
     * </ul>
     * <p>
     * <b>使用场景：</b>
     * <pre>{@code
     * // Session 关闭时调用
     * @Override
     * public void close() {
     *     EventDispatchers.release(eventDispatcher, this);
     *     // ... 其他清理逻辑
     * }
     * }</pre>
     *
     * @param dispatcher 要释放的调度器（可以为 null）
     * @param session    关联的会话
     */
    public static void release(EventDispatcher dispatcher, Session session) {
        if (dispatcher == null) {
            return;
        }

        if (dispatcher == SHARED_DISPATCHER) {
            // 共享调度器：只移除该 session 的 handlers
            dispatcher.removeHandlersForSession(session);
            LOG.debug("Removed handlers for session {} from shared dispatcher", session.getId());
        } else {
            // 专用调度器：完全关闭
            dispatcher.close();
            LOG.debug("Closed dedicated dispatcher for session {}", session.getId());
        }
    }

    /**
     * 关闭共享调度器
     * <p>
     * 停止所有分片的 Agent 线程，清理所有 handler 和事件队列。
     * 此方法只能成功执行一次，重复调用会被忽略。
     * <p>
     * <b>使用场景：</b>
     * <ul>
     *   <li>服务器关闭时调用</li>
     *   <li>应在所有 Session 关闭后调用</li>
     * </ul>
     * <p>
     * <b>注意：</b>
     * <ul>
     *   <li>调用后共享调度器将不可用</li>
     *   <li>未处理的事件会被丢弃</li>
     *   <li>确保在应用程序关闭流程中调用此方法</li>
     * </ul>
     */
    public static void shutdownSharedDispatcher() {
        if (SHARED_CLOSED.compareAndSet(false, true)) {
            LOG.info("Shutting down shared ShardedEventDispatcher with {} shards",
                    SHARED_DISPATCHER.shardCount());
            SHARED_DISPATCHER.close();
        }
    }

    // ==================== 监控方法 ====================

    /**
     * 获取共享调度器的总队列大小
     * <p>
     * 返回所有分片队列中待处理事件的总数。
     * 用于监控整体事件积压情况。
     * <p>
     * <b>使用场景：</b>
     * <ul>
     *   <li>性能监控：检测是否有事件积压</li>
     *   <li>压力测试：观察高负载下的队列状态</li>
     *   <li>告警：队列大小超过阈值时触发告警</li>
     * </ul>
     *
     * @return 所有分片队列大小之和
     */
    public static int sharedQueueSize() {
        return SHARED_DISPATCHER.aggregateQueueSize();
    }

    /**
     * 获取共享调度器的分片数量
     * <p>
     * 返回共享 ShardedEventDispatcher 中的分片个数。
     *
     * @return 分片数量（2 的幂，如 4, 8, 16）
     */
    public static int sharedShardCount() {
        return SHARED_DISPATCHER.shardCount();
    }

    /**
     * 获取共享调度器每个分片的队列大小
     * <p>
     * 返回每个分片队列中待处理事件的数量数组。
     * 用于检查负载均衡情况，识别热点分片。
     * <p>
     * <b>使用场景：</b>
     * <ul>
     *   <li>负载均衡验证：检查各分片负载是否均匀</li>
     *   <li>热点识别：找出负载过高的分片</li>
     *   <li>性能调优：分析分片策略的效果</li>
     * </ul>
     * <p>
     * <b>示例输出：</b>
     * <pre>
     * [150, 148, 162, 155, 149, 153, 158, 159]  // 8 分片，负载均衡良好
     * [1000, 10, 12, 8, 15, 9, 11, 10]          // 分片 0 负载过高，存在热点
     * </pre>
     *
     * @return 每个分片的队列大小数组（长度等于分片数）
     */
    public static int[] sharedShardQueueSizes() {
        return SHARED_DISPATCHER.shardQueueSizes();
    }
}
