package com.akakata.event.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 事件调度器监控指标收集器
 * <p>
 * 周期性输出 {@link ShardedEventDispatcher} 的队列状态，用于监控和性能分析。
 * 可以观察：
 * <ul>
 *   <li>总队列积压量（所有分片的事件总数）</li>
 *   <li>每个分片的队列大小（观察负载均衡情况）</li>
 *   <li>热点分片识别（某个分片是否负载过高）</li>
 * </ul>
 * <p>
 * <b>使用场景：</b>
 * <ul>
 *   <li>压力测试：观察高负载下的队列积压情况</li>
 *   <li>负载均衡验证：确认各分片负载是否均匀</li>
 *   <li>性能调优：识别瓶颈分片</li>
 *   <li>生产环境监控：及时发现事件积压问题</li>
 * </ul>
 * <p>
 * <b>输出示例：</b>
 * <pre>
 * EventDispatcher queue size total=1234, perShard=[150, 148, 162, 155, 149, 153, 158, 159]
 * </pre>
 * <p>
 * <b>使用方法：</b>
 * <pre>{@code
 * // 创建 metrics 收集器
 * EventDispatcherMetrics metrics = new EventDispatcherMetrics();
 *
 * // 每 5 秒输出一次监控数据
 * metrics.start(5);
 *
 * // 停止监控
 * metrics.stop();
 * }</pre>
 *
 * @author Kelvin
 */
public final class EventDispatcherMetrics {

    private static final Logger LOG = LoggerFactory.getLogger(EventDispatcherMetrics.class);

    // ==================== 字段 ====================

    /**
     * 定时任务调度器
     * <p>
     * 使用单线程的 ScheduledExecutorService，周期性执行监控任务。
     * 线程为守护线程，不会阻止 JVM 退出。
     */
    private final ScheduledExecutorService scheduler;

    /**
     * 启动状态标志
     * <p>
     * 使用 AtomicBoolean 确保 start() 方法只能被成功调用一次。
     */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * 监控任务
     * <p>
     * 定期执行此任务，收集并输出 EventDispatcher 的队列状态。
     */
    private final Runnable task = () -> {
        // 获取所有分片的总队列大小
        int total = EventDispatchers.sharedQueueSize();

        // 获取每个分片的队列大小数组
        int[] perShard = EventDispatchers.sharedShardQueueSizes();

        // 输出监控日志
        LOG.info("EventDispatcher queue size total={}, perShard={}", total, Arrays.toString(perShard));
    };

    // ==================== 构造函数 ====================

    /**
     * 构造监控指标收集器
     * <p>
     * 创建单线程的定时任务调度器，线程名为 "event-dispatcher-metrics"，
     * 并设置为守护线程。
     */
    public EventDispatcherMetrics() {
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "event-dispatcher-metrics");
            t.setDaemon(true);  // 守护线程，不阻止 JVM 退出
            return t;
        };
        this.scheduler = new ScheduledThreadPoolExecutor(1, factory);
    }

    // ==================== 生命周期方法 ====================

    /**
     * 启动监控
     * <p>
     * 开始周期性输出 EventDispatcher 的队列状态。
     * 此方法只能被成功调用一次，重复调用会被忽略。
     * <p>
     * <b>注意：</b>
     * <ul>
     *   <li>如果 intervalSeconds <= 0，监控将被禁用</li>
     *   <li>首次输出会在 intervalSeconds 秒后发生</li>
     *   <li>之后每隔 intervalSeconds 秒输出一次</li>
     * </ul>
     *
     * @param intervalSeconds 输出间隔（秒），必须 > 0 才会启用监控
     */
    public void start(long intervalSeconds) {
        if (intervalSeconds <= 0) {
            LOG.info("Event dispatcher metrics disabled (intervalSeconds <= 0)");
            return;
        }

        // CAS 操作确保只启动一次
        if (started.compareAndSet(false, true)) {
            // 以固定频率执行任务
            scheduler.scheduleAtFixedRate(
                    task,
                    intervalSeconds,    // 初始延迟
                    intervalSeconds,    // 执行间隔
                    TimeUnit.SECONDS
            );

            LOG.info("Event dispatcher metrics reporting every {} seconds with {} shards",
                    intervalSeconds, EventDispatchers.sharedShardCount());
        }
    }

    /**
     * 停止监控
     * <p>
     * 立即关闭定时任务调度器，停止所有监控任务。
     * 如果任务正在执行，会尝试中断。
     */
    public void stop() {
        scheduler.shutdownNow();
        LOG.info("Event dispatcher metrics stopped");
    }
}
