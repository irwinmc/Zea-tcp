package com.akakata.service;

import com.akakata.app.PlayerSession;
import com.akakata.app.Session;
import com.akakata.app.impl.DefaultGame;
import com.akakata.communication.MessageSender;
import com.akakata.security.SimpleCredentials;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies cache replacement, identity-based removal and cleanup during concurrent lifecycle
 * operations.
 */
@Timeout(10)
class SessionCacheLifecycleRegressionTest {
    /**
     * Creates a session cache lifecycle regression test instance.
     */
    SessionCacheLifecycleRegressionTest() {
    }

    /**
     * Game used by this regression fixture.
     */
    private DefaultGame game;
    /**
     * Service used by this regression fixture.
     */
    private SessionService service;
    /**
     * Credentials used by this regression fixture.
     */
    private SimpleCredentials credentials;

    /**
     * Sets the up.
     */
    @BeforeEach
    void setUp() {
        game = new DefaultGame();
        credentials = new SimpleCredentials();
        service = new SessionService(buffer -> credentials, false);
    }

    /**
     * Releases the resources used by this test fixture.
     */
    @AfterEach
    void tearDown() {
        service.close();
        game.close();
    }

    /**
     * Verifies the regression scenario: overwriting with put closes previous session.
     */
    @Test
    void overwritingWithPutClosesPreviousSession() {
        PlayerSession previous = game.createPlayerSession();
        PlayerSession replacement = game.createPlayerSession();
        assertTrue(game.connectSession(previous));
        service.putSession(credentials, previous);

        service.putSession(credentials, replacement);

        assertEquals(Session.Status.CLOSED, previous.getStatus());
        assertFalse(game.getSessions().contains(previous));
        assertSame(replacement, service.getSession(credentials));
    }

    /**
     * Verifies the regression scenario: replacing with same session keeps it open.
     */
    @Test
    void replacingWithSameSessionKeepsItOpen() {
        PlayerSession session = game.createPlayerSession();
        service.putSession(credentials, session);

        assertSame(session, service.replaceSession(credentials, session));

        assertFalse(session.isShuttingDown());
        assertSame(session, service.getSession(credentials));
    }

    /**
     * Verifies the regression scenario: remove closes session before returning.
     */
    @Test
    void removeClosesSessionBeforeReturning() {
        PlayerSession session = game.createPlayerSession();
        service.putSession(credentials, session);

        assertTrue(service.removeSession(credentials));

        assertEquals(Session.Status.CLOSED, session.getStatus());
        assertNull(service.getSession(credentials));
    }

    /**
     * Verifies the regression scenario: direct close removes cached session.
     */
    @Test
    void directCloseRemovesCachedSession() {
        PlayerSession session = game.createPlayerSession();
        service.putSession(credentials, session);

        session.close();

        assertEquals(0, service.size());
        assertNull(service.getSession(credentials));
    }

    /**
     * Verifies the regression scenario: changing credential attributes does not lose cached
     * session.
     */
    @Test
    void changingCredentialAttributesDoesNotLoseCachedSession() {
        PlayerSession session = game.createPlayerSession();
        service.putSession(credentials, session);

        credentials.setAttribute("role", "player");

        assertSame(session, service.getSession(credentials));
        assertTrue(service.removeSession(credentials));
        assertEquals(Session.Status.CLOSED, session.getStatus());
    }

    /**
     * Releases the resources used by this test fixture.
     */
    @Test
    void shutdownDoesNotHoldServiceLockDuringSessionCallbacks() {
        PlayerSession session = game.createPlayerSession();
        service.putSession(credentials, session);
        AtomicBoolean callbackCompleted = new AtomicBoolean();
        session.setSender(new MessageSender() {
            @Override
            public Object sendMessage(Object message) {
                return null;
            }

            @Override
            public void close() {
                var done = new CompletableFuture<Boolean>();
                Thread.startVirtualThread(() -> done.complete(service.putSession(credentials, session)));
                try {
                    assertFalse(done.get(1, TimeUnit.SECONDS));
                    callbackCompleted.set(true);
                } catch (Exception ignored) {
                    // Report the timeout after shutdown completes, without leaking blocked test threads.
                }
            }
        });

        service.close();

        assertTrue(callbackCompleted.get(), "Shutdown must not wait for session callbacks while holding the service lock");
    }

    /**
     * Verifies the regression scenario: old disconnect cannot remove new login.
     */
    @Test
    void oldDisconnectCannotRemoveNewLogin() {
        PlayerSession old = game.createPlayerSession();
        PlayerSession replacement = game.createPlayerSession();
        service.putSession(credentials, old);
        service.replaceSession(credentials, replacement);

        assertFalse(service.removeSession(credentials, old));
        old.close();

        assertSame(replacement, service.getSession(credentials));
        assertFalse(replacement.isShuttingDown());
    }

    /**
     * Verifies the regression scenario: concurrent replacements keep only the latest session.
     *
     * @throws Exception if the operation fails
     */
    @Test
    void concurrentReplacementsKeepOnlyTheLatestSession() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        var players = new ArrayList<PlayerSession>();
        var tasks = new ArrayList<CompletableFuture<Void>>();
        for (int i = 0; i < 32; i++) {
            PlayerSession player = game.createPlayerSession();
            players.add(player);
            CompletableFuture<Void> done = new CompletableFuture<>();
            tasks.add(done);
            Thread.startVirtualThread(() -> {
                try {
                    assertTrue(start.await(2, TimeUnit.SECONDS));
                    service.replaceSession(credentials, player);
                    done.complete(null);
                } catch (Throwable failure) {
                    done.completeExceptionally(failure);
                }
            });
        }
        start.countDown();
        CompletableFuture.allOf(tasks.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);

        PlayerSession latest = service.getSession(credentials);
        assertNotNull(latest);
        assertFalse(latest.isShuttingDown());
        assertEquals(1, service.size());
        for (PlayerSession player : players) {
            if (player != latest) {
                assertEquals(Session.Status.CLOSED, player.getStatus());
            }
        }
        latest.close();
        assertEquals(0, service.size());
    }

    /**
     * Releases the resources used by this test fixture.
     *
     * @throws Exception if the operation fails
     */
    @Test
    void shutdownWaitsForAnAlreadyRunningSessionCleanup() throws Exception {
        PlayerSession session = game.createPlayerSession();
        service.putSession(credentials, session);
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        session.setSender(new MessageSender() {
            @Override
            public Object sendMessage(Object message) { return null; }

            @Override
            public void close() {
                closing.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        });
        CompletableFuture<Void> firstClose = new CompletableFuture<>();
        CompletableFuture<Void> shutdown = new CompletableFuture<>();
        Thread.startVirtualThread(() -> {
            try {
                session.close();
                firstClose.complete(null);
            } catch (Throwable failure) {
                firstClose.completeExceptionally(failure);
            }
        });
        assertTrue(closing.await(2, TimeUnit.SECONDS));
        Thread.startVirtualThread(() -> {
            try {
                service.close();
                shutdown.complete(null);
            } catch (Throwable failure) {
                shutdown.completeExceptionally(failure);
            }
        });
        try {
            assertThrows(TimeoutException.class, () -> shutdown.get(200, TimeUnit.MILLISECONDS),
                    "Service shutdown must wait until the in-flight sender cleanup finishes");
        } finally {
            release.countDown();
            firstClose.get(2, TimeUnit.SECONDS);
            shutdown.get(2, TimeUnit.SECONDS);
        }
        assertNull(session.getSender());
        assertEquals(0, service.size());
    }
}
