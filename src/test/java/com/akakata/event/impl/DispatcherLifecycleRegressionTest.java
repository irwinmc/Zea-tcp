package com.akakata.event.impl;

import com.akakata.app.Session;
import com.akakata.app.impl.DefaultSession;
import com.akakata.event.Event;
import com.akakata.event.EventHandler;
import com.akakata.event.Events;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies dispatcher closure, late submissions and handler-initiated self or cross-dispatcher
 * shutdown.
 */
@Timeout(15)
class DispatcherLifecycleRegressionTest {

    /**
     * Creates a dispatcher lifecycle regression test instance.
     */
    DispatcherLifecycleRegressionTest() {
    }

    /**
     * Temp dir used by this regression fixture.
     */
    @TempDir
    Path tempDir;

    /**
     * Rejects initialization after terminal dispatcher closure.
     */
    @Test
    void closedDispatcherRejectsInitialization() {
        var dispatcher = new NettyEventDispatcher();
        dispatcher.close();
        assertThrows(IllegalStateException.class, dispatcher::initialize);
    }

    /**
     * Releases the resources used by this test fixture.
     */
    @Test
    void closedDispatcherDoesNotRetainNewEvents() {
        var dispatcher = new NettyEventDispatcher();
        dispatcher.close();

        dispatcher.fireEvent(Events.event("discard", Events.START));

        assertEquals(0, dispatcher.getQueueSize());
    }

    /**
     * Releases the resources used by this test fixture.
     */
    @Test
    void closedDispatcherRejectsNewHandlers() {
        var dispatcher = new NettyEventDispatcher();
        dispatcher.close();
        assertThrows(IllegalStateException.class, () -> dispatcher.addHandler(handler(Events.START, () -> {})));
        assertTrue(dispatcher.getHandlers(Events.START).isEmpty());
    }

    /**
     * Verifies the regression scenario: concurrent publishers cannot leave events after close.
     *
     * @throws Exception if the operation fails
     */
    @Test
    void concurrentPublishersCannotLeaveEventsAfterClose() throws Exception {
        var dispatcher = new NettyEventDispatcher();
        CountDownLatch start = new CountDownLatch(1);
        var tasks = new ArrayList<CompletableFuture<Void>>();
        for (int i = 0; i < 4; i++) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            tasks.add(done);
            Thread.startVirtualThread(() -> {
                try {
                    assertTrue(start.await(2, TimeUnit.SECONDS));
                    for (int j = 0; j < 500; j++) {
                        dispatcher.fireEvent(Events.event(j, Events.START));
                    }
                    done.complete(null);
                } catch (Throwable failure) {
                    done.completeExceptionally(failure);
                }
            });
        }
        start.countDown();
        try {
            dispatcher.close();
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(3, TimeUnit.SECONDS);
            assertEquals(0, dispatcher.getQueueSize());
        } finally {
            dispatcher.close();
        }
    }

    /**
     * Verifies the regression scenario: handler snapshot does not invoke a closed session.
     *
     * @throws Exception if the operation fails
     */
    @Test
    void handlerSnapshotDoesNotInvokeAClosedSession() throws Exception {
        runProbe(true);
    }

    /**
     * Verifies the regression scenario: session can close its dedicated dispatcher from inside a
     * handler.
     *
     * @throws Exception if the operation fails
     */
    @Test
    void sessionCanCloseItsDedicatedDispatcherFromInsideAHandler() throws Exception {
        runProbe(false);
    }

    /**
     * Verifies the regression scenario: run probe.
     *
     * @param snapshot the snapshot
     * @throws Exception if the operation fails
     */
    private void runProbe(boolean snapshot) throws Exception {
        runProbe(snapshot, ShutdownProbe.class.getName());
    }

    /**
     * Verifies the regression scenario: handlers can close each others dispatchers without
     * deadlocking.
     *
     * @throws Exception if the operation fails
     */
    @Test
    void handlersCanCloseEachOthersDispatchersWithoutDeadlocking() throws Exception {
        runProbe(false, CrossCloseProbe.class.getName());
    }

    /**
     * Lets a running callback close a session while an external closer waits for that callback.
     *
     * @throws Exception if the isolated probe deadlocks or leaks its executor
     */
    @Test
    void callbackSessionCloseDoesNotJoinAnExternalCloser() throws Exception {
        runProbe(false, ReentrantCloseProbe.class.getName());
    }

    /**
     * Verifies the regression scenario: run probe.
     *
     * @param snapshot the snapshot
     * @param probeClass the probe class
     * @throws Exception if the operation fails
     */
    private void runProbe(boolean snapshot, String probeClass) throws Exception {
        Path output = tempDir.resolve(probeClass.substring(probeClass.lastIndexOf('$') + 1) + snapshot + ".log");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                probeClass, Boolean.toString(snapshot))
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(7, TimeUnit.SECONDS),
                    () -> "Handler close deadlocked or leaked an executor: " + read(output));
            assertEquals(0, process.exitValue(), () -> read(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(2, TimeUnit.SECONDS));
            }
        }
    }

    /**
     * Runs a cross-dispatcher close scenario in an isolated JVM.
     */
    public static class CrossCloseProbe {

        /**
         * Creates a cross close probe instance.
         */
        public CrossCloseProbe() {
        }

        /**
         * Runs this executable example or isolated regression probe.
         *
         * @param args the command-line arguments
         * @throws Exception if the operation fails
         */
        public static void main(String[] args) throws Exception {
            var first = new NettyEventDispatcher();
            var second = new NettyEventDispatcher();
            var firstClosed = new CompletableFuture<Void>();
            var secondClosed = new CompletableFuture<Void>();
            var bothStarted = new CountDownLatch(2);
            first.addHandler(handler(Events.STOP, () -> crossClose(second, bothStarted, firstClosed)));
            second.addHandler(handler(Events.STOP, () -> crossClose(first, bothStarted, secondClosed)));
            first.initialize();
            second.initialize();
            try {
                first.fireEvent(Events.event(null, Events.STOP));
                second.fireEvent(Events.event(null, Events.STOP));
                CompletableFuture.allOf(firstClosed, secondClosed).get(1, TimeUnit.SECONDS);
                first.close();
                second.close();
            } finally {
                first.close();
                second.close();
            }
        }

        /**
         * Verifies the regression scenario: cross close.
         *
         * @param other the other
         * @param bothStarted the both started
         * @param done the done
         */
        private static void crossClose(NettyEventDispatcher other, CountDownLatch bothStarted,
                                       CompletableFuture<Void> done) {
            bothStarted.countDown();
            try {
                assertTrue(bothStarted.await(2, TimeUnit.SECONDS));
                other.close();
                done.complete(null);
            } catch (Throwable failure) {
                done.completeExceptionally(failure);
            }
        }
    }

    /**
     * Runs a callback/external-session-close race in a separate JVM.
     */
    public static class ReentrantCloseProbe {

        /**
         * Creates this isolated probe.
         */
        public ReentrantCloseProbe() {
        }

        /**
         * Coordinates an external dispatcher close with a reentrant session callback.
         *
         * @param args unused command-line arguments
         * @throws Exception if shutdown fails to complete
         */
        public static void main(String[] args) throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch closing = new CountDownLatch(1);
            var dispatcher = new NettyEventDispatcher() {
                @Override
                public void close() {
                    closing.countDown();
                    super.close();
                }
            };
            Session session = new DefaultSession.SessionBuilder().eventDispatcher(dispatcher).build();
            CompletableFuture<Void> callback = new CompletableFuture<>();
            dispatcher.addHandler(handler(Events.STOP, () -> {
                entered.countDown();
                try {
                    assertTrue(closing.await(2, TimeUnit.SECONDS));
                    session.close();
                    callback.complete(null);
                } catch (Throwable failure) {
                    callback.completeExceptionally(failure);
                }
            }));
            try {
                session.onEvent(Events.event(null, Events.STOP));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                CompletableFuture<Void> external = CompletableFuture.runAsync(session::close);
                CompletableFuture.allOf(callback, external).get(3, TimeUnit.SECONDS);
                assertEquals(Session.Status.CLOSED, session.getStatus());
                dispatcher.close();
            } finally {
                dispatcher.close();
                EventDispatchers.shutdownSharedDispatcher();
            }
        }
    }

    /**
     * Verifies the regression scenario: read.
     *
     * @param output the output
     * @return the value used by the regression scenario
     */
    private static String read(Path output) {
        try {
            return Files.readString(output);
        } catch (Exception e) {
            return e.toString();
        }
    }

    /**
     * Runs lifecycle shutdown checks in a separate JVM to isolate global dispatchers.
     */
    public static class ShutdownProbe {

        /**
         * Creates a shutdown probe instance.
         */
        public ShutdownProbe() {
        }

        /**
         * Runs this executable example or isolated regression probe.
         *
         * @param args the command-line arguments
         * @throws Exception if the operation fails
         */
        public static void main(String[] args) throws Exception {
            var dispatcher = new NettyEventDispatcher();
            Session session = new DefaultSession.SessionBuilder().eventDispatcher(dispatcher).build();
            boolean snapshot = Boolean.parseBoolean(args[0]);
            AtomicInteger deliveries = new AtomicInteger();
            var closed = new CompletableFuture<Void>();
            dispatcher.addHandler(handler(snapshot ? Events.ANY : Events.LOG_OUT, () -> {
                session.close();
                closed.complete(null);
            }));
            if (snapshot) {
                session.addHandler(new DefaultSessionEventHandler(session) {
                    @Override
                    protected void onCustomEvent(Event event) { deliveries.incrementAndGet(); }
                });
            }
            dispatcher.initialize();
            try {
                session.onEvent(Events.event(null, snapshot ? Events.START : Events.LOG_OUT));
                closed.get(1, TimeUnit.SECONDS);
                dispatcher.close();
                assertEquals(Session.Status.CLOSED, session.getStatus());
                assertEquals(0, dispatcher.getQueueSize());
                assertTrue(dispatcher.getHandlers(Events.LOG_OUT).isEmpty());
                assertEquals(0, deliveries.get(), "A handler captured before close must not run after the session closes");
            } finally {
                dispatcher.close();
                EventDispatchers.shutdownSharedDispatcher();
            }
        }
    }

    /**
     * Verifies the regression scenario: handler.
     *
     * @param type the event opcode
     * @param action the action
     * @return the value used by the regression scenario
     */
    private static EventHandler handler(int type, Runnable action) {
        return new EventHandler() {
            @Override
            public int getEventType() { return type; }

            @Override
            public void onEvent(Event event) { action.run(); }
        };
    }
}
