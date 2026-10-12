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
