package com.akakata.server;

import com.akakata.context.ConfigurationManager;
import com.akakata.lifecycle.ServerLifecycle;
import com.akakata.server.impl.ServerManagerImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies startup/shutdown publication and independent cleanup of failing listeners.
 */
@Timeout(10)
class ServerManagerLifecycleRegressionTest {

    /**
     * Creates the listener lifecycle fixture.
     */
    ServerManagerLifecycleRegressionTest() {
    }

    /**
     * Attempts every listener even when their shutdown methods all fail.
     *
     * @throws Exception if setup fails
     */
    @Test
    void stopFailureDoesNotSkipOtherListeners() throws Exception {
        RecordingServer first = new RecordingServer(true);
        RecordingServer second = new RecordingServer(true);
        RecordingServer third = new RecordingServer(true);
        ServerLifecycle lifecycle = new ServerLifecycle();
        ServerManagerImpl manager = new ServerManagerImpl(first, second, third,
                new ConfigurationManager("conf-lifecycle.properties"), lifecycle);
        manager.startServers(0, 1, 2, 3);
        Exception failure = assertThrows(Exception.class, manager::stopServers);
        assertEquals(1, first.stops.get());
        assertEquals(1, second.stops.get());
        assertEquals(1, third.stops.get());
        assertEquals(2, failure.getSuppressed().length);
        assertEquals(ServerLifecycle.Phase.STOPPING, lifecycle.phase());
    }

    /**
     * A late startup completion cannot restore readiness after shutdown is requested.
     *
     * @throws Exception if a barrier or lifecycle operation fails
     */
    @Test
    void shutdownWinsAgainstLateStartupPublication() throws Exception {
        RecordingServer listener = new RecordingServer(false);
        listener.entered = new CountDownLatch(1);
        listener.release = new CountDownLatch(1);
        ServerLifecycle lifecycle = new ServerLifecycle();
        ServerManagerImpl manager = new ServerManagerImpl(listener, new RecordingServer(false), new RecordingServer(false),
                new ConfigurationManager("conf-lifecycle.properties"), lifecycle);
        CompletableFuture<Void> startup = CompletableFuture.runAsync(() -> {
            try {
                manager.startServers(0, 1, 0, 0);
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        CompletableFuture<Void> shutdown = null;
        try {
            assertTrue(listener.entered.await(2, TimeUnit.SECONDS));
            shutdown = CompletableFuture.runAsync(() -> {
                try {
                    manager.stopServers();
                } catch (Exception failure) {
                    throw new RuntimeException(failure);
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (lifecycle.phase() != ServerLifecycle.Phase.STOPPING && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(ServerLifecycle.Phase.STOPPING, lifecycle.phase());
        } finally {
            listener.release.countDown();
            startup.get(3, TimeUnit.SECONDS);
            if (shutdown != null) {
                shutdown.get(3, TimeUnit.SECONDS);
            }
        }
        assertEquals(ServerLifecycle.Phase.STOPPING, lifecycle.phase());
        assertEquals(1, listener.stops.get());
        assertThrows(IllegalStateException.class, () -> manager.startServers(0, 1, 0, 0));
    }

    /**
     * Reads listener state promptly during a blocked bind and excludes disabled listeners.
     *
     * @throws Exception if a startup barrier fails
     */
    @Test
    void listenerReadinessDoesNotAcquireTheBlockingStartupMonitor() throws Exception {
        RecordingServer listener = new RecordingServer(false);
        listener.entered = new CountDownLatch(1);
        listener.release = new CountDownLatch(1);
        ListenerRegistry registry = new ListenerRegistry();
        ServerManagerImpl manager = new ServerManagerImpl(listener, new RecordingServer(false), new RecordingServer(false),
                new ConfigurationManager("conf-lifecycle.properties"), new ServerLifecycle(), registry);
        CompletableFuture<Void> startup = CompletableFuture.runAsync(() -> {
            try {
                manager.startServers(0, 1, 0, 0);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
        try {
            assertTrue(listener.entered.await(2, TimeUnit.SECONDS));
            assertFalse(CompletableFuture.supplyAsync(registry::isReady).get(1, TimeUnit.SECONDS));
            assertEquals(java.util.Map.of("tcp", false), registry.snapshot());
            listener.release.countDown();
            startup.get(2, TimeUnit.SECONDS);
            assertTrue(registry.isReady());
            listener.listening = false;
            assertFalse(registry.isReady());
        } finally {
            listener.release.countDown();
            startup.get(2, TimeUnit.SECONDS);
            manager.stopServers();
        }
    }

    /**
     * Requires every expected listener and treats throwing status implementations as unavailable.
     */
    @Test
    void readinessRequiresAllSelectedListenersAndFailsClosed() {
        ListenerRegistry registry = new ListenerRegistry();
        assertFalse(registry.isReady());
        RecordingServer active = new RecordingServer(false);
        active.listening = true;
        RecordingServer unavailable = new RecordingServer(false);
        registry.expect(java.util.Map.of("tcp", active, "http", unavailable));
        assertFalse(registry.isReady());
        unavailable.listening = true;
        assertTrue(registry.isReady());
        unavailable.statusFailure = true;
        assertFalse(registry.isReady());
        assertEquals(false, registry.snapshot().get("http"));
        registry.expect(java.util.Map.of());
        assertFalse(registry.isReady());
    }

    /**
     * Records listener operations without owning actual sockets.
     */
    private static final class RecordingServer implements Server {

        /**
         * Whether shutdown injects a failure.
         */
        private final boolean failStop;

        /**
         * Shutdown invocation count.
         */
        private final AtomicInteger stops = new AtomicInteger();

        /**
         * Optional startup entry barrier.
         */
        private CountDownLatch entered;

        /**
         * Optional startup release barrier.
         */
        private CountDownLatch release;

        /**
         * Actual synthetic bind availability, independent of startup's lock.
         */
        private volatile boolean listening;

        /**
         * Injected failure in the nonblocking status provider.
         */
        private boolean statusFailure;

        /**
         * Selects the fixture's shutdown behavior.
         *
         * @param failStop whether shutdown throws
         */
        private RecordingServer(boolean failStop) {
            this.failStop = failStop;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void startServer() throws Exception {
            if (entered != null) {
                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
            }
            listening = true;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isListening() {
            if (statusFailure) {
                throw new IllegalStateException("Injected listener status failure");
            }
            return listening;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void startServer(int port) throws Exception {
            startServer();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void startServer(InetSocketAddress address) throws Exception {
            startServer();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void stopServer() throws Exception {
            listening = false;
            stops.incrementAndGet();
            if (failStop) {
                throw new IOException("Injected listener close failure");
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public InetSocketAddress getSocketAddress() {
            return new InetSocketAddress("127.0.0.1", 0);
        }
    }
}
