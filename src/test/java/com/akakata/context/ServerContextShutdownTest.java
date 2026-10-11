package com.akakata.context;

import com.akakata.app.Game;
import com.akakata.app.PlayerSession;
import com.akakata.app.Session;
import com.akakata.app.impl.AbstractGame;
import com.akakata.communication.MessageSender;
import com.akakata.metrics.EventDispatcherMetrics;
import com.akakata.security.SimpleCredentials;
import com.akakata.service.SessionService;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies application-resource shutdown and partial initialization in isolated JVM processes.
 */
class ServerContextShutdownTest {

    /**
     * Selects the isolated server-context shutdown scenario.
     */
    enum Scenario {
        /**
         * Initialized used by this regression fixture.
         */
        INITIALIZED,
        /**
         * Uninitialized used by this regression fixture.
         */
        UNINITIALIZED,
        /**
         * Game close failure used by this regression fixture.
         */
        GAME_CLOSE_FAILURE,
        /**
         * Initialization failure used by this regression fixture.
         */
        INITIALIZATION_FAILURE
    }

    /**
     * Creates a server context shutdown test instance.
     */
    ServerContextShutdownTest() {
    }

    /**
     * Temp dir used by this regression fixture.
     */
    @TempDir
    Path tempDir;

    /**
     * Releases the resources used by this test fixture.
     *
     * @param scenario the scenario
     * @throws Exception if the operation fails
     */
    @ParameterizedTest
    @EnumSource(Scenario.class)
    @Timeout(20)
    void shutdownReleasesOwnedResources(Scenario scenario) throws Exception {
        // Closing ServerContext irreversibly stops process-wide singletons. Each probe
        // gets its own JVM so these tests cannot disable another test's shared dispatcher.
        Path output = tempDir.resolve(scenario + ".log");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
                "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                "-cp", classpath, ShutdownProbe.class.getName(), scenario.name())
                .redirectErrorStream(true)
                .redirectOutput(output.toFile())
                .start();
        try {
            assertTrue(process.waitFor(12, TimeUnit.SECONDS),
                    () -> "Shutdown left the JVM running: " + readOutput(output));
            assertEquals(0, process.exitValue(), () -> readOutput(output));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Verifies the regression scenario: read output.
     *
     * @param path the resource or file path
     * @return the value used by the regression scenario
     */
    private static String readOutput(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception e) {
            return "Cannot read shutdown probe output: " + e;
        }
    }

    /**
     * Runs lifecycle shutdown checks in a separate JVM to isolate global dispatchers.
     */
    public static final class ShutdownProbe {
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
            Scenario scenario = Scenario.valueOf(args[0]);
            ServerContext context = new ServerContext("conf-lifecycle.properties");
            Game game = null;
            SessionService sessions = null;
            try {
                if (scenario == Scenario.UNINITIALIZED) {
                    context.close();
                    context.close();
                    assertThrows(IllegalStateException.class, context::initialize);
                    assertNull(context.getBean(Game.class));
                    assertNull(context.getBean(SessionService.class));
                } else if (scenario == Scenario.INITIALIZATION_FAILURE) {
                    // A rejected metrics task fails initialization after Game and SessionService exist.
                    context.getConfigManager().getProperties().setProperty("metrics.event.interval.seconds", "1");
                    EventDispatcherMetrics.getInstance().stop();
                    assertThrows(RejectedExecutionException.class, context::initialize);
                    game = context.getBean(Game.class);
                    sessions = context.getBean(SessionService.class);
                    assertTrue(((AbstractGame) game).isShuttingDown());
                    ScheduledExecutorService cleanup = (ScheduledExecutorService) field(sessions, "cleanupExecutor");
                    assertTrue(cleanup.isTerminated());
                    assertThrows(IllegalStateException.class, context::initialize);
                } else {
                    context.initialize();
                    game = context.getBean(Game.class);
                    sessions = context.getBean(SessionService.class);
                    ScheduledExecutorService cleanup = (ScheduledExecutorService) field(sessions, "cleanupExecutor");

                    PlayerSession connected = game.createPlayerSession();
                    RecordingSender connectedSender = new RecordingSender();
                    connected.setSender(connectedSender);
                    assertTrue(game.connectSession(connected));
                    game.onLogin(connected);
                    sessions.putSession(new SimpleCredentials(), connected);

                    // Failed/in-flight logins may exist in the cache without joining the room.
                    PlayerSession pending = game.createPlayerSession();
                    RecordingSender pendingSender = new RecordingSender();
                    pending.setSender(pendingSender);
                    sessions.putSession(new SimpleCredentials(), pending);

                    AtomicBoolean gameCloseAttempted = new AtomicBoolean();
                    if (scenario == Scenario.GAME_CLOSE_FAILURE) {
                        Game ownedGame = game;
                        Game failingGame = (Game) Proxy.newProxyInstance(Game.class.getClassLoader(),
                                new Class<?>[]{Game.class}, (proxy, method, arguments) -> {
                                    if (method.getName().equals("close")) {
                                        gameCloseAttempted.set(true);
                                        ownedGame.close();
                                        throw new IllegalStateException("Injected game shutdown failure");
                                    }
                                    return method.invoke(ownedGame, arguments);
                                });
                        Field gameField = ServerContext.class.getDeclaredField("game");
                        gameField.setAccessible(true);
                        gameField.set(context, failingGame);
                    }

                    context.close();
                    context.close();

                    assertTrue(((AbstractGame) game).isShuttingDown(), "The owned game must be closed");
                    assertTrue(game.getSessions().isEmpty());
                    assertEquals(Session.Status.CLOSED, connected.getStatus());
                    assertEquals(Session.Status.CLOSED, pending.getStatus());
                    assertTrue(connectedSender.closed);
                    assertTrue(pendingSender.closed);
                    assertEquals(0, sessions.size());
                    assertTrue(cleanup.isTerminated(), "Session cleanup executor must terminate");
                    assertThrows(IllegalStateException.class, context::initialize);
                    if (scenario == Scenario.GAME_CLOSE_FAILURE) {
                        assertTrue(gameCloseAttempted.get());
                    }
                }

                NetworkBootstrap network = (NetworkBootstrap) field(context, "networkBootstrap");
                assertTrue(network.getBossGroup().isTerminated());
                assertTrue(network.getWorkerGroup().isTerminated());
                assertTrue(Thread.getAllStackTraces().keySet().stream()
                        .noneMatch(t -> t.isAlive() && t.getName().equals("agrona-event-dispatcher")),
                        "Room and shared dispatcher threads must terminate before close returns");
            } finally {
                // Clean up even on a regression, so the assertion is reported instead of hanging.
                if (game != null) {
                    game.close();
                }
                if (sessions != null) {
                    sessions.close();
                }
                context.close();
            }
            // Natural JVM exit also verifies that no non-daemon framework threads remain.
        }

        /**
         * Verifies the regression scenario: field.
         *
         * @param owner the owner
         * @param name the parameter or metric name
         * @return the value used by the regression scenario
         * @throws Exception if the operation fails
         */
        private static Object field(Object owner, String name) throws Exception {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(owner);
        }

        /**
         * Records outbound messages and close calls for lifecycle assertions.
         */
        private static final class RecordingSender implements MessageSender {
            /**
             * Creates a recording sender instance.
             */
            private RecordingSender() {
            }

            /**
             * Terminal lifecycle flag, published when shutdown begins.
             */
            private boolean closed;

            /**
             * {@inheritDoc}
             */
            @Override
            public Object sendMessage(Object message) {
                return message;
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void close() {
                closed = true;
            }
        }
    }
}
