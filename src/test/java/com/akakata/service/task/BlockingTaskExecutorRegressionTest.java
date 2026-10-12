package com.akakata.service.task;

import com.akakata.app.impl.DefaultGame;
import com.akakata.event.Event;
import com.akakata.event.EventHandler;
import com.akakata.event.Events;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies bounded slow-operation admission, cancellation ownership and ordered result delivery.
 */
@Timeout(10)
class BlockingTaskExecutorRegressionTest {

    /**
     * Creates the blocking operation fixture.
     */
    BlockingTaskExecutorRegressionTest() {
    }

    /**
     * Rejects count and byte overflow without running rejected work on the caller.
     *
     * @throws Exception if a worker does not reach its barrier
     */
    @Test
    void countAndByteBudgetsIncludeRunningWorkAndNeverUseCallerRuns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean rejectedRan = new AtomicBoolean();
        try (BlockingTaskExecutor executor = new BlockingTaskExecutor(1, 1, 64, 1_000)) {
            CompletableFuture<String> first = executor.submit(32, () -> {
                started.countDown();
                release.await();
                return Thread.currentThread().getName();
            });
            try {
                assertTrue(started.await(2, TimeUnit.SECONDS));
                CompletableFuture<Integer> second = executor.submit(32, () -> 2);
                CompletableFuture<Integer> rejected = executor.submit(0, () -> {
                    rejectedRan.set(true);
                    return 3;
                });
                assertInstanceOf(RejectedExecutionException.class, assertThrows(ExecutionException.class,
                        () -> rejected.get(1, TimeUnit.SECONDS)).getCause());
                assertEquals(2, executor.pendingTasks());
                assertEquals(64, executor.pendingBytes());
                assertFalse(rejectedRan.get());
                release.countDown();
                assertTrue(first.get(2, TimeUnit.SECONDS).startsWith("zea-blocking-"));
                assertEquals(2, second.get(2, TimeUnit.SECONDS));
                assertEquals(0, executor.pendingBytes());
            } finally {
                release.countDown();
            }
            assertInstanceOf(RejectedExecutionException.class, assertThrows(ExecutionException.class,
                    () -> executor.submit(65, () -> 1).get()).getCause());
        }
    }

    /**
     * Frees a cancelled queued operation while retaining an interrupted running operation's charge.
     *
     * @throws Exception if cancellation barriers are not reached
     */
    @Test
    void cancellationCannotReturnRunningResourcesBeforeTheOperationExits() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BlockingTaskExecutor executor = new BlockingTaskExecutor(1, 1, 64, 1_000);
        AtomicBoolean queuedRan = new AtomicBoolean();
        try {
            var running = executor.submit(32, () -> {
                started.countDown();
                awaitIgnoringInterrupt(release, interrupted);
                return 1;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = executor.submit(32, () -> {
                queuedRan.set(true);
                return 2;
            });
            assertTrue(queued.cancel(true));
            assertEquals(1, executor.pendingTasks());
            assertEquals(32, executor.pendingBytes());
            assertTrue(running.cancel(true));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(1, executor.pendingTasks());
            assertEquals(32, executor.pendingBytes());
            assertInstanceOf(RejectedExecutionException.class, assertThrows(ExecutionException.class,
                    () -> executor.submit(33, () -> 3).get()).getCause());
            assertFalse(queuedRan.get());
        } finally {
            release.countDown();
            executor.close();
        }
        assertTrue(executor.isTerminated());
        assertEquals(0, executor.pendingTasks());
        assertEquals(0, executor.pendingBytes());
        assertTrue(executor.submit(0, () -> 1).isCompletedExceptionally());
    }

    /**
     * Bounds closure while an external library ignores interruption, without pretending it released memory.
     *
     * @throws Exception if the worker does not start or terminate after release
     */
    @Test
    void uncooperativeWorkHasBoundedCloseAndKeepsItsReservation() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BlockingTaskExecutor executor = new BlockingTaskExecutor(1, 1, 64, 20);
        try {
            var running = executor.submit(64, () -> {
                started.countDown();
                awaitIgnoringInterrupt(release, new CountDownLatch(0));
                return 1;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            long start = System.nanoTime();
            executor.close();
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1_000);
            assertTrue(running.isCancelled());
            assertFalse(executor.isTerminated());
            assertEquals(64, executor.pendingBytes());
        } finally {
            release.countDown();
            executor.close();
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!executor.isTerminated() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertTrue(executor.isTerminated());
        assertEquals(0, executor.pendingBytes());
    }

    /**
     * Keeps Game callbacks responsive and applies worker results through the existing ordered event queue.
     *
     * @throws Exception if a worker or Game barrier does not complete
     */
    @Test
    void slowWorkDoesNotBlockGameEventsAndResultsRunOnTheOriginalGameExecutor() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ordinaryEvents = new CountDownLatch(2);
        CountDownLatch resultDelivered = new CountDownLatch(1);
        List<Integer> observed = new ArrayList<>();
        AtomicReference<String> callbackThread = new AtomicReference<>();
        AtomicReference<String> workerThread = new AtomicReference<>();
        try (BlockingTaskExecutor executor = new BlockingTaskExecutor(1, 2, 64, 1_000);
             DefaultGame game = new DefaultGame()) {
            game.getEventDispatcher().addHandler(new EventHandler() {
                @Override
                public int getEventType() {
                    return 1_000_020;
                }
                @Override
                public void onEvent(Event event) {
                    String previous = callbackThread.getAndSet(Thread.currentThread().getName());
                    if (previous != null) {
                        assertEquals(previous, Thread.currentThread().getName());
                    }
                    int value = (int) event.getSource();
                    observed.add(value);
                    if (value == 3) {
                        resultDelivered.countDown();
                    }
                    else {
                        ordinaryEvents.countDown();
                    }
                }
            });
            var operation = executor.submit(32, () -> {
                workerThread.set(Thread.currentThread().getName());
                started.countDown();
                release.await();
                return 3;
            });
            try {
                var posted = operation.thenApply(value -> game.send(Events.event(value, 1_000_020)));
                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertTrue(game.send(Events.event(1, 1_000_020)));
                assertTrue(game.send(Events.event(2, 1_000_020)));
                assertTrue(ordinaryEvents.await(2, TimeUnit.SECONDS), "Slow I/O must not block Game callbacks");
                release.countDown();
                assertTrue(posted.get(2, TimeUnit.SECONDS));
                assertTrue(resultDelivered.await(2, TimeUnit.SECONDS));
                assertEquals(List.of(1, 2, 3), observed);
                assertNotEquals(workerThread.get(), callbackThread.get());
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * Rejects late Session results after closure through the existing session event admission.
     *
     * @throws Exception if the worker does not complete
     */
    @Test
    void closedSessionRejectsLateResultsWithoutInvokingItsHandler() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (BlockingTaskExecutor executor = new BlockingTaskExecutor(1, 1, 64, 1_000);
             DefaultGame game = new DefaultGame()) {
            var session = game.createPlayerSession();
            var operation = executor.submit(32, () -> {
                started.countDown();
                release.await();
                return 1;
            });
            try {
                var posted = operation.thenApply(value -> session.onEvent(Events.event(value, 1_000_021)));
                assertTrue(started.await(2, TimeUnit.SECONDS));
                session.close();
                release.countDown();
                assertFalse(posted.get(2, TimeUnit.SECONDS));
                assertEquals(0, executor.pendingBytes());
            } finally {
                release.countDown();
                session.close();
            }
        }
    }

    /**
     * Avoids waiting for the closing worker itself while preserving cancellation accounting.
     *
     * @throws Exception if a worker does not complete the closure barrier
     */
    @Test
    void closureFromAnOwnedWorkerDoesNotWaitForItself() throws Exception {
        BlockingTaskExecutor executor = new BlockingTaskExecutor(1, 1, 64, 5_000);
        CountDownLatch closed = new CountDownLatch(1);
        try {
            var future = executor.submit(32, () -> {
                executor.close();
                closed.countDown();
                return 1;
            });
            assertTrue(closed.await(2, TimeUnit.SECONDS), "A worker must not wait for its own termination");
            assertTrue(future.isCancelled());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!executor.isTerminated() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertTrue(executor.isTerminated());
            assertEquals(0, executor.pendingBytes());
        } finally {
            executor.close();
        }
    }

    /**
     * Simulates an external dependency that ignores interruption until explicitly released.
     *
     * @param release the eventual operation completion signal
     * @param interrupted the observable interruption signal
     */
    private static void awaitIgnoringInterrupt(CountDownLatch release, CountDownLatch interrupted) {
        while (release.getCount() != 0) {
            try {
                release.await();
            }
            catch (InterruptedException ignored) {
                interrupted.countDown();
            }
        }
    }
}
