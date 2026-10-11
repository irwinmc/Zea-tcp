package com.akakata.event.impl;

import com.akakata.event.Event;
import com.akakata.event.EventDispatcher;
import com.akakata.event.EventHandler;
import com.akakata.event.Events;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.RejectedExecutionHandlers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic executor barriers and paused queues for dispatcher regressions.
 */
final class DispatcherTestSupport {

    /**
     * Prevents construction of this test utility.
     */
    private DispatcherTestSupport() {
    }

    /**
     * Waits for tasks published before a barrier, without sleeping or calling a consumer manually.
     *
     * @param dispatcher the dispatcher to drain
     * @throws InterruptedException if the waiting test is interrupted
     */
    static void drain(EventDispatcher dispatcher) throws InterruptedException {
        CountDownLatch completed = new CountDownLatch(1);
        if (dispatcher instanceof NettyEventDispatcher netty) {
            assertTrue(netty.execute(Events.event(null, 1_000_001), event -> completed.countDown()));
            assertTrue(completed.await(3, TimeUnit.SECONDS), "Dispatcher barrier did not complete");
            return;
        }
        EventHandler barrier = handler(1_000_001, event -> completed.countDown());
        dispatcher.addHandler(barrier);
        try {
            dispatcher.fireEvent(Events.event(null, barrier.getEventType()));
            assertTrue(completed.await(3, TimeUnit.SECONDS), "Dispatcher barrier did not complete");
        } finally {
            dispatcher.removeHandler(barrier);
        }
    }

    /**
     * Creates a typed callback for a regression scenario.
     *
     * @param type the subscribed event type
     * @param action the callback action
     * @return the callback adapter
     */
    static EventHandler handler(int type, java.util.function.Consumer<Event> action) {
        return new EventHandler() {
            @Override
            public int getEventType() { return type; }
            @Override
            public void onEvent(Event event) { action.accept(event); }
        };
    }

    /**
     * Holds a borrowed dispatcher behind a latch so queued payload ownership is observable.
     */
    static final class PausedDispatcher implements AutoCloseable {

        /**
         * Executor owner, independent of the scope being closed by the test.
         */
        private final DefaultEventExecutorGroup group;

        /**
         * Release signal for the first task occupying the worker.
         */
        private final CountDownLatch release = new CountDownLatch(1);

        /**
         * Borrowed scope with a deterministic, paused task queue.
         */
        final NettyEventDispatcher dispatcher;

        /**
         * Starts one blocked executor with the requested bounded queue.
         *
         * @param capacity the pending-task capacity
         * @throws InterruptedException if worker startup is interrupted
         */
        PausedDispatcher(int capacity) throws InterruptedException {
            group = new DefaultEventExecutorGroup(1, Thread.ofPlatform().name("paused-test-", 0).factory(),
                    capacity, RejectedExecutionHandlers.reject());
            var executor = group.next();
            dispatcher = new NettyEventDispatcher(executor, capacity);
            CountDownLatch started = new CountDownLatch(1);
            executor.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            try {
                assertTrue(started.await(3, TimeUnit.SECONDS));
            } catch (InterruptedException | AssertionError failure) {
                release.countDown();
                group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
                throw failure;
            }
        }

        /**
         * Releases the paused worker without closing its borrowed registration scope.
         */
        void resume() {
            release.countDown();
        }

        /**
         * Releases the paused worker after cancelling any remaining scope deliveries.
         */
        @Override
        public void close() {
            try {
                dispatcher.close();
            } finally {
                release.countDown();
                group.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
            }
        }
    }
}
