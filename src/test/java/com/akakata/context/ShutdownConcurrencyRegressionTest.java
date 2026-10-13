package com.akakata.context;

import com.akakata.app.Game;
import com.akakata.app.PlayerSession;
import com.akakata.app.impl.DefaultGame;
import com.akakata.app.impl.DefaultSession;
import com.akakata.event.Events;
import com.akakata.event.impl.NettyEventDispatcher;
import com.akakata.lifecycle.ServerLifecycle;
import com.akakata.lifecycle.ShutdownTimeoutException;
import com.akakata.metrics.ServerHealth;
import com.akakata.server.ServerManager;
import com.akakata.server.impl.AbstractNettyServer;
import io.netty.channel.ServerChannel;
import com.akakata.service.task.BlockingTaskExecutor;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises callback, I/O and timeout shutdown paths in independent JVMs.
 */
class ShutdownConcurrencyRegressionTest {

    /**
     * Process-isolated lifecycle scenarios.
     */
    enum Scenario {
        /**
         * External and business callback closures race for the same context.
         */
        CALLBACK_RACE,

        /**
         * Context closure is requested from a network worker.
         */
        IO_CONTEXT,

        /**
         * Network ownership is closed from its own worker.
         */
        IO_NETWORK,

        /**
         * Detached application work requests context shutdown.
         */
        BLOCKING_CONTEXT,

        /**
         * An inline resource completion continuation requests context closure.
         */
        COMPLETION_CALLBACK,

        /**
         * Application cleanup reenters its own context closure.
         */
        CLEANUP_REENTRY,

        /**
         * A session close listener requests its owning context's cleanup.
         */
        SESSION_CLOSE_CALLBACK,

        /**
         * Shutdown races with an application factory that has not returned yet.
         */
        INITIALIZATION_RACE,

        /**
         * A factory requests closure before returning its allocated resource.
         */
        FACTORY_REENTRY,

        /**
         * A stalled Game does not prevent later network cleanup.
         */
        CONTEXT_TIMEOUT,

        /**
         * The default composition reports actual Game and lifecycle availability.
         */
        HEALTH_GRAPH,

        /**
         * Withdraws readiness when a bound listener disappears without closing its event loops.
         */
        LISTENER_LOSS,

        /**
         * Observes real worker termination after a bounded context shutdown wait expires.
         */
        WORKER_TERMINATION
    }

    /**
     * Child-process diagnostics.
     */
    @TempDir
    Path directory;

    /**
     * Creates the concurrency fixture.
     */
    ShutdownConcurrencyRegressionTest() {
    }

    /**
     * Requires each shutdown probe to terminate its JVM with no leaked owned threads.
     *
     * @param scenario child scenario
     * @throws Exception if launching or waiting for the probe fails
     */
    @ParameterizedTest
    @EnumSource(Scenario.class)
    @Timeout(20)
    void closesWithoutCyclesOrAbandonedResources(Scenario scenario) throws Exception {
        Path log = directory.resolve(scenario + ".log");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, Probe.class.getName(), scenario.name())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(12, TimeUnit.SECONDS), () -> diagnostics(log));
            assertEquals(0, child.exitValue(), () -> diagnostics(log));
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(2, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Reads bounded child diagnostics after a failed assertion.
     *
     * @param log diagnostic file
     * @return diagnostic content
     */
    private static String diagnostics(Path log) {
        try {
            return Files.readString(log);
        } catch (Exception failure) {
            return failure.toString();
        }
    }

    /**
     * Runs scenarios without resetting terminal process-wide lifecycle state.
     */
    public static final class Probe {

        /**
         * Prevents construction of this executable fixture.
         */
        private Probe() {
        }

        /**
         * Dispatches one isolated shutdown scenario.
         *
         * @param arguments scenario name
         * @throws Exception if cleanup or a barrier fails
         */
        public static void main(String[] arguments) throws Exception {
            Scenario scenario = Scenario.valueOf(arguments[0]);
            if (scenario == Scenario.INITIALIZATION_RACE || scenario == Scenario.FACTORY_REENTRY) {
                closesDuringInitialization(scenario);
                return;
            }
            if (scenario == Scenario.IO_NETWORK) {
                NetworkBootstrap network = new NetworkBootstrap(1, 1, 1000);
                CompletableFuture<Void> returned = new CompletableFuture<>();
                network.getWorkerGroup().next().execute(() -> {
                    try {
                        network.close();
                        returned.complete(null);
                    } catch (Throwable failure) {
                        returned.completeExceptionally(failure);
                    }
                });
                returned.get(2, TimeUnit.SECONDS);
                network.closeAsync().get(3, TimeUnit.SECONDS);
                return;
            }
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch gameClosing = new CountDownLatch(1);
            AtomicReference<ServerContext> owner = new AtomicReference<>();
            ServerComponents components = ServerComponents.builder().gameFactory(configuration -> new DefaultGame(16, 4096) {
                @Override
                public void close() {
                    gameClosing.countDown();
                    if (scenario == Scenario.CLEANUP_REENTRY) {
                        owner.get().close();
                    }
                    super.close();
                }
            }).build();
            ServerContext context = new ServerContext(scenario == Scenario.CONTEXT_TIMEOUT || scenario == Scenario.WORKER_TERMINATION
                    ? "conf-shutdown-fast.properties" : "conf-lifecycle.properties", components);
            owner.set(context);
            try {
                if (scenario == Scenario.HEALTH_GRAPH || scenario == Scenario.LISTENER_LOSS) {
                    context.getConfigManager().getProperties().setProperty("server.http.enabled", "true");
                    context.getConfigManager().getProperties().setProperty("http.port", "0");
                }
                if (scenario == Scenario.WORKER_TERMINATION) {
                    context.getConfigManager().getProperties().setProperty("business.blocking.shutdown.millis", "0");
                }
                context.initialize();
                Game game = context.getBean(Game.class);
                if (scenario == Scenario.CALLBACK_RACE || scenario == Scenario.CONTEXT_TIMEOUT) {
                    CompletableFuture<Void> callbackReturned = new CompletableFuture<>();
                    assertTrue(((NettyEventDispatcher) game.getEventDispatcher()).execute(Events.event(null, 77), event -> {
                        entered.countDown();
                        await(release);
                        if (scenario == Scenario.CALLBACK_RACE) {
                            context.close();
                        }
                        callbackReturned.complete(null);
                    }));
                    assertTrue(entered.await(2, TimeUnit.SECONDS));
                    if (scenario == Scenario.CALLBACK_RACE) {
                        CompletableFuture<Void> external = CompletableFuture.runAsync(context::close);
                        assertTrue(gameClosing.await(2, TimeUnit.SECONDS));
                        release.countDown();
                        callbackReturned.get(2, TimeUnit.SECONDS);
                        external.get(3, TimeUnit.SECONDS);
                    } else {
                        assertThrows(ShutdownTimeoutException.class, context::close);
                        assertFalse(context.closeAsync().isDone());
                        NetworkBootstrap network = context.getBean(NetworkBootstrap.class);
                        network.getWorkerGroup().terminationFuture().get(2, TimeUnit.SECONDS);
                        assertEquals(ServerLifecycle.Phase.STOPPING, context.getLifecycle().phase());
                        release.countDown();
                        callbackReturned.get(2, TimeUnit.SECONDS);
                    }
                } else if (scenario == Scenario.IO_CONTEXT) {
                    CompletableFuture<Void> returned = new CompletableFuture<>();
                    context.getBean(NetworkBootstrap.class).getWorkerGroup().next().execute(() -> {
                        try {
                            context.close();
                            returned.complete(null);
                        } catch (Throwable failure) {
                            returned.completeExceptionally(failure);
                        }
                    });
                    returned.get(2, TimeUnit.SECONDS);
                } else if (scenario == Scenario.BLOCKING_CONTEXT) {
                    CompletableFuture<Void> returned = new CompletableFuture<>();
                    context.getBean(BlockingTaskExecutor.class).submit(0, () -> {
                        context.close();
                        returned.complete(null);
                        return null;
                    });
                    returned.get(2, TimeUnit.SECONDS);
                } else if (scenario == Scenario.COMPLETION_CALLBACK) {
                    NetworkBootstrap foreign = new NetworkBootstrap(1, 1, 1000);
                    foreign.closeAsync().thenRun(context::close).get(2, TimeUnit.SECONDS);
                } else if (scenario == Scenario.SESSION_CLOSE_CALLBACK) {
                    PlayerSession session = game.createPlayerSession();
                    assertTrue(game.connectSession(session));
                    ((DefaultSession) session).addCloseListener(context::close);
                    session.close();
                } else if (scenario == Scenario.LISTENER_LOSS) {
                    ServerHealth health = context.getBean(ServerHealth.class);
                    context.getBean(ServerManager.class).startServers();
                    assertTrue(health.snapshot().ready());
                    int listeners = 0;
                    for (var channel : AbstractNettyServer.ALL_CHANNELS) {
                        if (channel instanceof ServerChannel) {
                            channel.close().sync();
                            listeners++;
                        }
                    }
                    assertEquals(1, listeners);
                    assertFalse(health.snapshot().ready());
                    assertTrue(health.snapshot().checks().get("network"));
                    assertEquals(ServerLifecycle.Phase.RUNNING, context.getLifecycle().phase());
                } else if (scenario == Scenario.WORKER_TERMINATION) {
                    BlockingTaskExecutor worker = context.getBean(BlockingTaskExecutor.class);
                    worker.submit(64, () -> {
                        entered.countDown();
                        boolean released = false;
                        while (!released) {
                            try {
                                released = release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException ignored) {
                                // Model a dependency that ignores cancellation.
                            }
                        }
                        return null;
                    });
                    assertTrue(entered.await(2, TimeUnit.SECONDS));
                    CompletableFuture<Void> cleanup = context.closeAsync();
                    context.getBean(NetworkBootstrap.class).getWorkerGroup().terminationFuture().get(2, TimeUnit.SECONDS);
                    assertThrows(java.util.concurrent.TimeoutException.class,
                            () -> cleanup.get(150, TimeUnit.MILLISECONDS));
                    assertThrows(ShutdownTimeoutException.class, context::close);
                    assertFalse(cleanup.isDone());
                    assertFalse(worker.isTerminated());
                    assertEquals(64, worker.pendingBytes());
                    assertEquals(ServerLifecycle.Phase.STOPPING, context.getLifecycle().phase());
                    release.countDown();
                    context.closeAsync().get(3, TimeUnit.SECONDS);
                    assertTrue(worker.isTerminated());
                    assertEquals(0, worker.pendingTasks());
                    assertEquals(0, worker.pendingBytes());
                } else if (scenario == Scenario.HEALTH_GRAPH) {
                    ServerHealth health = context.getBean(ServerHealth.class);
                    assertFalse(health.snapshot().ready());
                    context.getBean(ServerManager.class).startServers();
                    assertTrue(health.snapshot().ready());
                    game.close();
                    assertFalse(health.snapshot().ready());
                    assertFalse(health.snapshot().checks().get("game"));
                    context.closeAsync();
                    assertFalse(health.snapshot().ready());
                }
                context.closeAsync().get(4, TimeUnit.SECONDS);
                assertEquals(ServerLifecycle.Phase.TERMINATED, context.getLifecycle().phase());
            } finally {
                release.countDown();
                context.closeAsync().get(5, TimeUnit.SECONDS);
            }
        }

        /**
         * Verifies prompt cleanup requests and ownership of resources returned after closure.
         *
         * @param scenario initialization race or factory reentry
         * @throws Exception if a barrier or actual cleanup fails
         */
        private static void closesDuringInitialization(Scenario scenario) throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<ServerContext> owner = new AtomicReference<>();
            AtomicReference<DefaultGame> returned = new AtomicReference<>();
            ServerComponents components = ServerComponents.builder().gameFactory(configuration -> {
                entered.countDown();
                if (scenario == Scenario.FACTORY_REENTRY) {
                    owner.get().close();
                } else {
                    await(release);
                }
                DefaultGame game = new DefaultGame(16, 4096);
                returned.set(game);
                return game;
            }).build();
            ServerContext context = new ServerContext("conf-shutdown-fast.properties", components);
            owner.set(context);
            CompletableFuture<Void> initialization = CompletableFuture.runAsync(context::initialize);
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                if (scenario == Scenario.INITIALIZATION_RACE) {
                    CompletableFuture<Void> cleanup = CompletableFuture.supplyAsync(context::closeAsync)
                            .get(2, TimeUnit.SECONDS);
                    assertTimeout(java.time.Duration.ofSeconds(1),
                            () -> assertThrows(ShutdownTimeoutException.class, context::close));
                    context.getBean(NetworkBootstrap.class).getWorkerGroup().terminationFuture().get(2, TimeUnit.SECONDS);
                    assertFalse(cleanup.isDone());
                    assertEquals(ServerLifecycle.Phase.STOPPING, context.getLifecycle().phase());
                }
                release.countDown();
                java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> initialization.get(3, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
                context.closeAsync().get(3, TimeUnit.SECONDS);
                assertNotNull(returned.get());
                assertTrue(returned.get().isShuttingDown());
                ((NettyEventDispatcher) returned.get().getEventDispatcher()).closeAsync().get(2, TimeUnit.SECONDS);
                assertEquals(ServerLifecycle.Phase.TERMINATED, context.getLifecycle().phase());
            } finally {
                release.countDown();
                context.closeAsync().get(4, TimeUnit.SECONDS);
            }
        }

        /**
         * Waits for the owner to permit callback completion.
         *
         * @param latch test barrier
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
}
