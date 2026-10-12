package com.akakata.metrics;

import com.akakata.event.Events;
import com.akakata.event.impl.NettyEventDispatcher;
import com.akakata.lifecycle.ServerLifecycle;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies lifecycle-driven readiness independently of HTTP and concrete services.
 */
class HealthPolicyRegressionTest {

    /**
     * Creates the policy fixture.
     */
    HealthPolicyRegressionTest() {
    }

    /**
     * Requires successful startup and withdraws readiness immediately on shutdown.
     */
    @Test
    void readinessFollowsStartupResourcesAndShutdown() {
        ServerLifecycle lifecycle = new ServerLifecycle();
        AtomicBoolean resource = new AtomicBoolean(true);
        ServerHealth health = new DefaultServerHealth(lifecycle, Map.of("resource", resource::get));
        assertFalse(health.snapshot().live());
        assertFalse(health.snapshot().ready());
        lifecycle.initialized();
        assertTrue(health.snapshot().live());
        assertFalse(health.snapshot().ready());
        lifecycle.starting();
        assertFalse(health.snapshot().ready());
        lifecycle.running();
        assertTrue(health.snapshot().ready());
        resource.set(false);
        assertFalse(health.snapshot().ready());
        resource.set(true);
        lifecycle.stopping();
        lifecycle.running();
        assertEquals(ServerLifecycle.Phase.STOPPING, lifecycle.phase());
        assertFalse(health.snapshot().ready());
        lifecycle.terminated();
        assertFalse(health.snapshot().live());
        assertThrows(IllegalStateException.class, lifecycle::starting);
    }

    /**
     * Fails closed when an application availability check throws.
     */
    @Test
    void throwingCheckCannotReportReady() {
        ServerLifecycle lifecycle = new ServerLifecycle();
        lifecycle.starting();
        lifecycle.running();
        ServerHealth health = new DefaultServerHealth(lifecycle, Map.of("broken", () -> {
            throw new IllegalStateException("Injected health failure");
        }));
        assertFalse(health.snapshot().ready());
        assertFalse(health.snapshot().checks().get("broken"));
    }

    /**
     * Detects an aged callback while leaving its payload and executor ownership intact.
     *
     * @throws Exception if a test barrier or executor fails to complete
     */
    @Test
    void agedActiveWorkWithdrawsDispatcherAvailability() throws Exception {
        NettyEventDispatcher dispatcher = new NettyEventDispatcher(16, 1024);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertTrue(dispatcher.execute(Events.event(null, 77), event -> {
                entered.countDown();
                try {
                    assertTrue(release.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (dispatcher.isHealthy(1) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertFalse(dispatcher.isHealthy(1));
            assertFalse(dispatcher.closeAsync().isDone());
        } finally {
            release.countDown();
            dispatcher.closeAsync().get(2, TimeUnit.SECONDS);
        }
    }
}
