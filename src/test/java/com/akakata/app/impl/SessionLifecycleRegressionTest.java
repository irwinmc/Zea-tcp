package com.akakata.app.impl;

import com.akakata.app.PlayerSession;
import com.akakata.app.Session;
import com.akakata.communication.MessageSender;
import com.akakata.event.Events;
import com.akakata.event.impl.DefaultSessionEventHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies terminal session states, game membership and cleanup during concurrent closure.
 */
@Timeout(10)
class SessionLifecycleRegressionTest {
    /**
     * Creates a session lifecycle regression test instance.
     */
    SessionLifecycleRegressionTest() {
    }

    /**
     * Game used by this regression fixture.
     */
    private DefaultGame game;

    /**
     * Sets the up.
     */
    @BeforeEach
    void setUp() {
        game = new DefaultGame();
    }

    /**
     * Releases the resources used by this test fixture.
     */
    @AfterEach
    void tearDown() {
        game.close();
    }

    /**
     * Releases the resources used by this test fixture.
     */
    @Test
    void closedSessionCannotReconnectOrChangeStatus() {
        PlayerSession session = game.createPlayerSession();
        session.close();

        assertFalse(game.connectSession(session));
        session.setStatus(Session.Status.CONNECTED);
        assertEquals(Session.Status.CLOSED, session.getStatus());
        assertTrue(game.getSessions().isEmpty());
    }

    /**
     * Verifies the regression scenario: late sender is closed instead of attached.
     */
    @Test
    void lateSenderIsClosedInsteadOfAttached() {
        PlayerSession session = game.createPlayerSession();
        session.close();
        RecordingSender sender = new RecordingSender();

        session.setSender(sender);

        assertEquals(1, sender.closeCalls.get());
        assertNull(session.getSender());
    }

    /**
     * Verifies the regression scenario: late login does not register handlers.
     */
    @Test
    void lateLoginDoesNotRegisterHandlers() {
        PlayerSession session = game.createPlayerSession();
        session.close();

        game.onLogin(session);

        assertTrue(session.getEventHandlers(Events.ANY).stream().noneMatch(handler ->
                handler instanceof DefaultSessionEventHandler listener && listener.getSession() == session));
    }

    /**
     * Verifies the regression scenario: sender failure still closes and disconnects session.
     */
    @Test
    void senderFailureStillClosesAndDisconnectsSession() {
        PlayerSession session = game.createPlayerSession();
        assertTrue(game.connectSession(session));
        session.setSender(new RecordingSender() {
            @Override
            public void close() {
                super.close();
                throw new IllegalStateException("Injected sender close failure");
            }
        });

        assertThrows(IllegalStateException.class, session::close);

        assertEquals(Session.Status.CLOSED, session.getStatus());
        assertNull(session.getSender());
        assertTrue(game.getSessions().isEmpty());
        assertDoesNotThrow(session::close);
    }

    /**
     * Verifies the regression scenario: room close allows concurrent disconnect.
     */
    @Test
    void roomCloseAllowsConcurrentDisconnect() {
        AtomicBoolean disconnected = new AtomicBoolean();
        PlayerSession session = new DefaultPlayerSession(new DefaultPlayerSession.PlayerSessionBuilder().game(game)) {
            @Override
            public void close() {
                var done = new java.util.concurrent.CompletableFuture<Boolean>();
                Thread.startVirtualThread(() -> done.complete(game.disconnectSession(this)));
                try {
                    disconnected.set(done.get(1, TimeUnit.SECONDS));
                } catch (Exception ignored) {
                    // The assertion below reports lock inversion without leaving deadlocked test threads.
                } finally {
                    super.close();
                }
            }
        };
        assertTrue(game.connectSession(session));

        game.close();

        assertTrue(disconnected.get(), "Game.close must release the room lock before closing sessions");
        assertTrue(game.getSessions().isEmpty());
    }

    /**
     * Releases the resources used by this test fixture.
     */
    @Test
    void closedRoomRejectsSessionCreation() {
        game.close();
        assertThrows(IllegalStateException.class, game::createPlayerSession);
    }

    /**
     * Verifies the regression scenario: concurrent connections cannot escape room shutdown.
     *
     * @throws Exception if the operation fails
     */
    @Test
    void concurrentConnectionsCannotEscapeRoomShutdown() throws Exception {
        var sessions = new ArrayList<PlayerSession>();
        var tasks = new ArrayList<CompletableFuture<Void>>();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 32; i++) {
            PlayerSession session = game.createPlayerSession();
            sessions.add(session);
            CompletableFuture<Void> done = new CompletableFuture<>();
            tasks.add(done);
            Thread.startVirtualThread(() -> {
                try {
                    assertTrue(start.await(2, TimeUnit.SECONDS));
                    game.connectSession(session);
                    done.complete(null);
                } catch (Throwable failure) {
                    done.completeExceptionally(failure);
                }
            });
        }
        CompletableFuture<Void> closing = new CompletableFuture<>();
        tasks.add(closing);
        Thread.startVirtualThread(() -> {
            try {
                assertTrue(start.await(2, TimeUnit.SECONDS));
                game.close();
                closing.complete(null);
            } catch (Throwable failure) {
                closing.completeExceptionally(failure);
            }
        });
        start.countDown();
        try {
            CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            assertTrue(game.getSessions().isEmpty());
            assertTrue(sessions.stream().noneMatch(Session::isConnected));
        } finally {
            sessions.forEach(Session::close);
        }
    }

    /**
     * Verifies the regression scenario: connection callback failure closes registered session.
     */
    @Test
    void connectionCallbackFailureClosesRegisteredSession() {
        DefaultGame failingGame = new DefaultGame() {
            @Override
            public void afterSessionConnect(PlayerSession session) {
                throw new IllegalStateException("Injected connection callback failure");
            }
        };
        try {
            PlayerSession session = failingGame.createPlayerSession();

            assertThrows(IllegalStateException.class, () -> failingGame.connectSession(session));

            assertEquals(Session.Status.CLOSED, session.getStatus());
            assertTrue(failingGame.getSessions().isEmpty());
        } finally {
            failingGame.close();
        }
    }

    /**
     * Records outbound messages and close calls for lifecycle assertions.
     */
    static class RecordingSender implements MessageSender {
        /**
         * Creates a recording sender instance.
         */
        RecordingSender() {
        }

        /**
         * Close calls used by this regression fixture.
         */
        final AtomicInteger closeCalls = new AtomicInteger();

        /**
         * {@inheritDoc}
         */
        @Override
        public Object sendMessage(Object message) {
            return null;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }
}
