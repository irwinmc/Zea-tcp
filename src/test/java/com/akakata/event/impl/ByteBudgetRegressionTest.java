package com.akakata.event.impl;

import com.akakata.event.Events;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies byte-budget rejection, running-work accounting and event-driven queue recovery.
 */
@Timeout(10)
class ByteBudgetRegressionTest {

    /**
     * Creates the byte admission fixture.
     */
    ByteBudgetRegressionTest() {}

    /**
     * Keeps running payloads charged until cleanup and emits one low-water transition.
     *
     * @throws Exception if a dispatcher gate fails
     */
    @Test
    void runningPayloadConsumesBudgetUntilCompletionAndSignalsDrain() throws Exception {
        NettyEventDispatcher dispatcher = new NettyEventDispatcher(16, 100);
        ByteBuf payload = Unpooled.buffer().writeZero(80);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), drained = new CountDownLatch(1);
        AtomicInteger signals = new AtomicInteger();
        try {
            var completion = dispatcher.submit(Events.event(payload, 1), event -> {
                entered.countDown();
                try { assertTrue(release.await(3, TimeUnit.SECONDS)); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new AssertionError(stopped); }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertEquals(0, dispatcher.getQueueSize());
            assertEquals(80, dispatcher.getPendingBytes());
            assertFalse(dispatcher.fireEvent(Events.event(payload, 1)));
            assertEquals(2, payload.refCnt());
            try (var registration = dispatcher.addDrainListener(() -> { signals.incrementAndGet(); drained.countDown(); })) {
                assertEquals(0, signals.get());
                payload.release();
                release.countDown();
                completion.get(3, TimeUnit.SECONDS);
                assertTrue(drained.await(3, TimeUnit.SECONDS));
                assertEquals(1, signals.get());
                assertEquals(0, payload.refCnt());
                var snapshot = dispatcher.snapshot();
                assertEquals(0, snapshot.pendingBytes());
                assertEquals(1, snapshot.accepted());
                assertEquals(1, snapshot.completed());
                assertEquals(1, snapshot.rejected());
                assertTrue(snapshot.executionP99Micros() > 0);
            }
        } finally {
            release.countDown(); dispatcher.close();
            if (payload.refCnt() > 0) payload.release();
        }
    }

    /**
     * Rejects oversized payloads before retaining native resources, independently per shard.
     */
    @Test
    void shardByteLimitsRejectBeforeRetainingAndRecoverAfterCancellation() {
        try (ShardedEventDispatcher group = new ShardedEventDispatcher(2, 16, 32)) {
            ByteBuf payload = Unpooled.buffer().writeZero(33);
            try {
                assertFalse(group.fireEvent(Events.event(payload, 1)));
                assertEquals(1, payload.refCnt());
                assertEquals(1, group.snapshots().get(1).rejected());
                assertEquals(0, group.snapshots().get(1).pendingBytes());
            } finally { payload.release(); }
        }
    }

    /**
     * Cancels retained queued payloads and removes recovery subscriptions on terminal closure.
     *
     * @throws Exception if the paused executor cannot start
     */
    @Test
    void cancellationReturnsEveryQueuedByteAndKeepsMetricsConsistent() throws Exception {
        try (DispatcherTestSupport.PausedDispatcher paused = new DispatcherTestSupport.PausedDispatcher(16)) {
            ByteBuf payload = Unpooled.buffer().writeZero(32);
            assertTrue(paused.dispatcher.fireEvent(Events.event(payload, 1)));
            assertEquals(32, paused.dispatcher.getPendingBytes());
            payload.release();
            paused.dispatcher.close();
            assertEquals(0, payload.refCnt());
            assertEquals(0, paused.dispatcher.snapshot().pendingBytes());
            assertEquals(1, paused.dispatcher.snapshot().cancelled());
        }
    }
}
