package com.akakata.service;

import com.akakata.app.PlayerSession;
import com.akakata.app.impl.DefaultGame;
import com.akakata.security.SimpleCredentials;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies admission limits, reconnect slot reuse and resource cleanup during full-capacity login.
 */
@Timeout(10)
class SessionCapacityRegressionTest {

    /**
     * Creates the regression fixture.
     */
    SessionCapacityRegressionTest() {
    }

    /**
     * Preserves existing online players and reuses their slot when reconnecting at capacity.
     */
    @Test
    void fullRegistryRejectsNewCredentialsWithoutEvictingExistingPlayers() {
        try (DefaultGame game = new DefaultGame()) {
            SessionService service = new SessionService(buffer -> null, false, 1);
            SimpleCredentials key = new SimpleCredentials();
            PlayerSession previous = game.createPlayerSession();
            PlayerSession rejected = game.createPlayerSession();
            PlayerSession replacement = game.createPlayerSession();
            try {
                assertTrue(game.connectSession(previous));
                assertTrue(service.putSession(key, previous));
                assertFalse(service.putSession(new SimpleCredentials(), rejected));
                assertFalse(previous.isShuttingDown());
                assertTrue(previous.isConnected());
                assertEquals(1, service.size());
                assertSame(previous, service.getSession(key));
                assertSame(previous, service.replaceSession(key, replacement));
                assertTrue(previous.isShuttingDown());
                assertSame(replacement, service.getSession(key));
                assertEquals(1, service.size());
                replacement.close();
                assertEquals(0, service.size());
                assertTrue(service.putSession(new SimpleCredentials(), rejected));
                assertFalse(rejected.isShuttingDown());
            } finally {
                rejected.close();
                replacement.close();
                service.close();
            }
        }
    }

    /**
     * Makes explicit registration rejection observable without taking ownership of the rejected session.
     */
    @Test
    void explicitCapacityFailureLeavesTheRejectedSessionOwnedByItsCaller() {
        try (DefaultGame game = new DefaultGame()) {
            SessionService service = new SessionService(buffer -> null, false, 1);
            PlayerSession first = game.createPlayerSession();
            PlayerSession second = game.createPlayerSession();
            try {
                assertTrue(service.putSession(new SimpleCredentials(), first));
                assertThrows(SessionCapacityException.class,
                        () -> service.replaceSession(new SimpleCredentials(), second));
                assertFalse(second.isShuttingDown());
                assertFalse(first.isShuttingDown());
            } finally {
                second.close();
                service.close();
            }
        }
    }

    /**
     * Prevents concurrent registrations from exceeding a strict slot budget.
     *
     * @throws Exception if concurrent registrations do not finish
     */
    @Test
    void concurrentLoginsCannotOversubscribeTheRegistryAndRejectedSessionsAreClosed() throws Exception {
        RecordingGame game = new RecordingGame();
        SessionService service = new SessionService(buffer -> new SimpleCredentials(), false, 4);
        LoginService login = new LoginService(service, new SecurityService());
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<?>[] attempts = new CompletableFuture<?>[24];
        AtomicInteger admitted = new AtomicInteger();
        try {
            for (int i = 0; i < attempts.length; i++) {
                CompletableFuture<Void> completion = new CompletableFuture<>();
                attempts[i] = completion;
                Thread.startVirtualThread(() -> {
                    var buffer = Unpooled.buffer().writeByte(1);
                    try {
                        assertTrue(start.await(3, TimeUnit.SECONDS));
                        if (login.login(buffer, game) != null) {
                            admitted.incrementAndGet();
                        }
                        completion.complete(null);
                    } catch (Throwable failure) {
                        completion.completeExceptionally(failure);
                    } finally {
                        buffer.release();
                    }
                });
            }
            start.countDown();
            CompletableFuture.allOf(attempts).get(5, TimeUnit.SECONDS);
            assertEquals(4, admitted.get());
            assertEquals(4, service.size());
            assertEquals(24, game.created.size());
            assertEquals(20, game.created.stream().filter(PlayerSession::isShuttingDown).count());
            service.close();
            assertTrue(game.created.stream().allMatch(PlayerSession::isShuttingDown));
        } finally {
            start.countDown();
            service.close();
            game.close();
        }
    }

    /**
     * Rejects invalid admission limits before constructing registry resources.
     */
    @Test
    void admissionLimitMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new SessionService(buffer -> null, false, 0));
        assertThrows(IllegalArgumentException.class, () -> new SessionService(buffer -> null, false, -1));
    }

    /**
     * Records sessions created during successful and rejected authentication attempts.
     */
    private static final class RecordingGame extends DefaultGame {

        /**
         * Every resource created by a concurrent authentication attempt.
         */
        private final List<PlayerSession> created = new CopyOnWriteArrayList<>();

        /**
         * Creates the authentication fixture.
         */
        private RecordingGame() {
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized PlayerSession createPlayerSession() {
            PlayerSession session = super.createPlayerSession();
            created.add(session);
            return session;
        }
    }
}
