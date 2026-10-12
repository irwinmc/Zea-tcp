package com.akakata.lifecycle;

import com.akakata.event.Events;
import com.akakata.event.impl.NettyEventDispatcher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies bounded observation without abandoning real cleanup or native ownership.
 */
@Timeout(10)
class ShutdownPolicyRegressionTest {

    /**
     * Creates the shutdown policy fixture.
     */
    ShutdownPolicyRegressionTest() {
    }

    /**
     * Keeps active payloads charged after an external wait expires.
     *
     * @throws Exception if callback cleanup fails
     */
    @Test
    void timedOutDispatcherKeepsPayloadUntilActualCallbackExit() throws Exception {
        NettyEventDispatcher dispatcher = new NettyEventDispatcher(16, 1024, 50);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ByteBuf payload = Unpooled.directBuffer().writeLong(1);
        try {
            assertTrue(dispatcher.execute(Events.event(payload, 77), event -> {
                entered.countDown();
                await(release);
            }));
            payload.release();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            CompletableFuture<Void> observation = dispatcher.closeAsync();
            assertTrue(observation.cancel(false));
            assertTimeout(java.time.Duration.ofSeconds(1),
                    () -> assertThrows(ShutdownTimeoutException.class, dispatcher::close));
            assertFalse(dispatcher.closeAsync().isDone());
            assertEquals(1, payload.refCnt());
            assertEquals(8, dispatcher.getPendingBytes());
            assertFalse(dispatcher.isHealthy(5000));
            release.countDown();
            dispatcher.closeAsync().get(2, TimeUnit.SECONDS);
            assertEquals(0, payload.refCnt());
            assertEquals(0, dispatcher.getPendingBytes());
        } finally {
            release.countDown();
            dispatcher.closeAsync().get(3, TimeUnit.SECONDS);
            if (payload.refCnt() > 0) {
                payload.release(payload.refCnt());
            }
        }
    }

    /**
     * Starts later cleanup stages even when an earlier action remains blocked.
     *
     * @throws Exception if the sequence fails to finish after releasing its barrier
     */
    @Test
    void stalledStageDoesNotSuppressLaterCleanup() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch later = new CountDownLatch(1);
        CompletableFuture<Void> completion = ShutdownSequence.start(List.of(
                new ShutdownSequence.Step("blocked", () -> {
                    entered.countDown();
                    await(release);
                }), new ShutdownSequence.Step("later", later::countDown)), 50);
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(later.await(2, TimeUnit.SECONDS));
            assertFalse(completion.isDone());
        } finally {
            release.countDown();
            completion.get(2, TimeUnit.SECONDS);
        }
    }

    /**
     * Propagates cleanup failures after giving every stage its cleanup opportunity.
     *
     * @throws Exception if later cleanup does not start
     */
    @Test
    void failedStageDoesNotSuppressLaterCleanup() throws Exception {
        CountDownLatch later = new CountDownLatch(1);
        CompletableFuture<Void> completion = ShutdownSequence.start(List.of(
                new ShutdownSequence.Step("failed", () -> {
                    throw new IllegalStateException("Injected cleanup failure");
                }), new ShutdownSequence.Step("later", later::countDown)), 500);
        assertTrue(later.await(2, TimeUnit.SECONDS));
        assertThrows(java.util.concurrent.ExecutionException.class, () -> completion.get(2, TimeUnit.SECONDS));
    }

    /**
     * Preserves interruption while observing actual cleanup.
     */
    @Test
    void interruptedCallerRetainsItsInterruptStatus() {
        Thread.currentThread().interrupt();
        try {
            ShutdownSupport.await(CompletableFuture.completedFuture(null), 100, "completed fixture");
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * Includes late owned resources without reporting completion before assembly finishes.
     *
     * @throws Exception if late cleanup does not execute
     */
    @Test
    void registryClosesLateResourcesAndWaitsForOwnershipToBeSealed() throws Exception {
        ShutdownRegistry registry = new ShutdownRegistry(50);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch lateClosed = new CountDownLatch(1);
        AtomicInteger closeCalls = new AtomicInteger();
        Object initial = new Object();
        registry.own(initial, "initial", 1, resource -> {
            closeCalls.incrementAndGet();
            entered.countDown();
            await(release);
        });
        CompletableFuture<Void> completion = registry.closeAsync();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            registry.own(initial, "duplicate", 1, resource -> fail("Duplicate ownership must not close twice"));
            registry.own(new Object(), "late", 2, resource -> lateClosed.countDown());
            assertTrue(lateClosed.await(2, TimeUnit.SECONDS));
            assertFalse(completion.isDone());
            registry.seal();
            assertFalse(completion.isDone());
        } finally {
            release.countDown();
            registry.seal();
            completion.get(2, TimeUnit.SECONDS);
        }
        assertEquals(1, closeCalls.get());
    }

    /**
     * Waits on a bounded test barrier rather than relying on arbitrary sleeps.
     *
     * @param latch barrier released by the test owner
     */
    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
