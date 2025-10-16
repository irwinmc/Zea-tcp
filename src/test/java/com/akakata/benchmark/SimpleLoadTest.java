package com.akakata.benchmark;

import com.akakata.event.Event;
import com.akakata.event.EventHandler;
import com.akakata.event.impl.AgronaEventDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple load test for AgronaEventDispatcher.
 * Tests throughput and latency under different load levels.
 *
 * @author Kelvin
 */
public class SimpleLoadTest {

    private static final Logger LOG = LoggerFactory.getLogger(SimpleLoadTest.class);

    public static void main(String[] args) throws Exception {
        // 配置参数
        int threads = 4;              // 并发线程数（可以调整）
        int eventsPerThread = 500_000; // 每个线程发送的事件数

        LOG.info("========================================");
        LOG.info("AgronaEventDispatcher 性能测试");
        LOG.info("========================================");
        LOG.info("配置:");
        LOG.info("  - 并发线程数: {}", threads);
        LOG.info("  - 每线程事件数: {}", eventsPerThread);
        LOG.info("  - 总事件数: {}", threads * eventsPerThread);
        LOG.info("========================================");

        // 创建 dispatcher
        AgronaEventDispatcher dispatcher = new AgronaEventDispatcher();
        dispatcher.initialize();

        AtomicLong processedCount = new AtomicLong(0);
        AtomicLong totalLatency = new AtomicLong(0);

        // 添加事件处理器
        dispatcher.addHandler(new EventHandler() {
            @Override
            public void onEvent(Event event) {
                // 记录处理的事件数
                processedCount.incrementAndGet();

                // 计算延迟（从事件创建到处理的时间）
                long latency = System.nanoTime() - event.getTimeStamp();
                totalLatency.addAndGet(latency);
            }

            @Override
            public int getEventType() {
                return 1001;
            }
        });

        // 预热阶段
        LOG.info("\n开始预热...");
        TestEvent warmupEvent = new TestEvent();
        for (int i = 0; i < 100_000; i++) {
            dispatcher.fireEvent(warmupEvent);
        }
        Thread.sleep(1000); // 等待预热完成

        // 重置计数器
        processedCount.set(0);
        totalLatency.set(0);

        LOG.info("预热完成，开始压测...\n");

        // 开始压测
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);  // 让所有线程同时开始
        CountDownLatch endLatch = new CountDownLatch(threads);

        long startTime = System.nanoTime();

        // 启动所有发送线程
        for (int i = 0; i < threads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // 等待开始信号

                    // 发送事件
                    for (int j = 0; j < eventsPerThread; j++) {
                        TestEvent event = new TestEvent();
                        dispatcher.fireEvent(event);
                    }

                    LOG.info("线程 {} 完成发送", threadId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 发送开始信号
        startLatch.countDown();

        // 等待所有线程发送完成
        endLatch.await();
        long sendEndTime = System.nanoTime();

        // 等待队列处理完成
        LOG.info("\n等待队列处理完成...");
        Thread.sleep(2000);

        long totalEndTime = System.nanoTime();

        // 计算结果
        long totalEvents = (long) threads * eventsPerThread;
        long processed = processedCount.get();

        double sendSeconds = (sendEndTime - startTime) / 1_000_000_000.0;
        double totalSeconds = (totalEndTime - startTime) / 1_000_000_000.0;

        double sendThroughput = totalEvents / sendSeconds;
        double processThroughput = processed / totalSeconds;

        double avgLatencyUs = (totalLatency.get() / (double) processed) / 1000.0;

        // 输出结果
        LOG.info("\n========================================");
        LOG.info("测试结果");
        LOG.info("========================================");
        LOG.info("发送阶段:");
        LOG.info("  - 总事件数: {}", totalEvents);
        LOG.info("  - 发送耗时: {:.2f} 秒", sendSeconds);
        LOG.info("  - 发送吞吐量: {:.0f} events/sec", sendThroughput);
        LOG.info("  - 发送吞吐量: {:.2f} M events/sec", sendThroughput / 1_000_000);

        LOG.info("\n处理阶段:");
        LOG.info("  - 已处理数: {}", processed);
        LOG.info("  - 处理耗时: {:.2f} 秒", totalSeconds);
        LOG.info("  - 处理吞吐量: {:.0f} events/sec", processThroughput);
        LOG.info("  - 处理吞吐量: {:.2f} M events/sec", processThroughput / 1_000_000);

        LOG.info("\n延迟:");
        LOG.info("  - 平均延迟: {:.2f} μs", avgLatencyUs);
        LOG.info("  - 平均延迟: {:.3f} ms", avgLatencyUs / 1000.0);

        LOG.info("\n准确性:");
        LOG.info("  - 丢失事件: {}", totalEvents - processed);
        LOG.info("  - 成功率: {:.2f}%", (processed * 100.0 / totalEvents));
        LOG.info("========================================");

        // 清理
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        dispatcher.close();

        LOG.info("\n测试完成！");
    }

    /**
     * 测试事件，包含时间戳用于计算延迟
     */
    static class TestEvent implements Event {
        private int type = 1001;
        private Object source;
        private long timestamp;

        public TestEvent() {
            this.timestamp = System.nanoTime();
        }

        @Override
        public int getType() {
            return type;
        }

        @Override
        public void setType(int type) {
            this.type = type;
        }

        @Override
        public Object getSource() {
            return source;
        }

        @Override
        public void setSource(Object source) {
            this.source = source;
        }

        @Override
        public long getTimeStamp() {
            return timestamp;
        }

        @Override
        public void setTimeStamp(long timeStamp) {
            this.timestamp = timeStamp;
        }
    }
}
