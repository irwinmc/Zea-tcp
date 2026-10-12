package com.akakata.communication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies atomic aggregate reservation under contention and overflow-safe accounting.
 */
@Timeout(10)
class OutboundBudgetRegressionTest {

    /**
     * Creates the aggregate reservation fixture.
     */
    OutboundBudgetRegressionTest() {
    }

    /**
     * Prevents concurrent producers from oversubscribing a shared allowance.
     *
     * @throws Exception if an independent producer fails or stalls
     */
    @Test
    void concurrentReservationsNeverExceedTheAggregateLimit() throws Exception {
        OutboundBudget budget = new OutboundBudget(8);
        CountDownLatch start = new CountDownLatch(1);
        try (var producers = Executors.newFixedThreadPool(16)) {
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 16; i++) {
                tasks.add(producers.submit(() -> {
                    start.await();
                    for (int attempt = 0; attempt < 1000; attempt++) {
                        if (budget.reserve(1)) {
                            try {
                                assertTrue(budget.pendingBytes() <= 8);
                            } finally {
                                budget.release(1);
                            }
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var task : tasks) {
                task.get(3, TimeUnit.SECONDS);
            }
        }
        assertEquals(0, budget.pendingBytes());
    }

    /**
     * Rejects invalid release and saturating reservations without wrapping signed longs.
     */
    @Test
    void maximumChargesCannotOverflowOrUnderflow() {
        OutboundBudget budget = new OutboundBudget(Long.MAX_VALUE);
        assertTrue(budget.reserve(Long.MAX_VALUE));
        assertFalse(budget.reserve(1));
        assertEquals(Long.MAX_VALUE, budget.pendingBytes());
        budget.release(Long.MAX_VALUE);
        assertEquals(0, budget.pendingBytes());
        assertThrows(IllegalArgumentException.class, () -> budget.release(1));
        assertThrows(IllegalArgumentException.class, () -> budget.reserve(-1));
        assertThrows(IllegalArgumentException.class, () -> new OutboundBudget(0));
    }
}
