package com.akakata.benchmark;

import com.akakata.event.Event;
import com.akakata.event.EventHandler;
import com.akakata.event.Events;
import com.akakata.event.impl.NettyEventDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs a local bounded-dispatcher diagnostic with explicit accepted and rejected counts.
 *
 * <p>This helper measures in-process callback throughput and mean latency. It is not
 * an end-to-end game-server capacity or tail-latency benchmark.
 */
public final class SimpleLoadTest {

    /**
     * Diagnostic output logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(SimpleLoadTest.class);

    /**
     * Prevents construction of this executable utility.
     */
    private SimpleLoadTest() {
    }

    /**
     * Submits events concurrently and waits for a serial executor barrier before reporting.
     *
     * @param args unused command-line arguments
     * @throws Exception if producers or the completion barrier fail
     */
    public static void main(String[] args) throws Exception {
        int producerCount = 4;
        int eventsPerProducer = 100_000;
        AtomicLong accepted = new AtomicLong();
        AtomicLong processed = new AtomicLong();
        AtomicLong latencyNanos = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<?>[] producers = new CompletableFuture<?>[producerCount];
        try (NettyEventDispatcher dispatcher = new NettyEventDispatcher()) {
            dispatcher.addHandler(new EventHandler() {
                @Override
                public int getEventType() { return 1001; }
                @Override
                public void onEvent(Event event) {
                    processed.incrementAndGet();
                    latencyNanos.addAndGet(System.nanoTime() - event.getTimeStamp());
                }
            });
            for (int i = 0; i < producerCount; i++) {
                CompletableFuture<Void> done = new CompletableFuture<>();
                producers[i] = done;
                Thread.startVirtualThread(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < eventsPerProducer; j++) {
                            Event event = Events.event(null, 1001);
                            event.setTimeStamp(System.nanoTime());
                            if (dispatcher.fireEvent(event)) {
                                accepted.incrementAndGet();
                            }
                        }
                        done.complete(null);
                    } catch (Throwable failure) {
                        done.completeExceptionally(failure);
                    }
                });
            }
            long beginning = System.nanoTime();
            start.countDown();
            CompletableFuture.allOf(producers).get(30, TimeUnit.SECONDS);
            CompletableFuture<Void> drained = new CompletableFuture<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!dispatcher.execute(Events.event(null, 1002), event -> drained.complete(null))) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("Dispatcher remained saturated");
                }
                Thread.sleep(1);
            }
            drained.get(30, TimeUnit.SECONDS);
            double seconds = (System.nanoTime() - beginning) / 1_000_000_000.0;
            long attempted = (long) producerCount * eventsPerProducer;
            if (accepted.get() != processed.get()) {
                throw new IllegalStateException("Accepted and processed event counts differ");
            }
            LOG.info("Netty dispatcher: attempted={}, accepted={}, rejected={}, processed={}",
                    attempted, accepted.get(), attempted - accepted.get(), processed.get());
            LOG.info("Completed callback throughput: {} events/s; mean latency: {} microseconds",
                    Math.round(processed.get() / seconds),
                    processed.get() == 0 ? 0 : latencyNanos.get() / (double) processed.get() / 1000.0);
        }
    }
}
