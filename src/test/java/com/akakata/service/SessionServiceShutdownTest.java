package com.akakata.service;

import com.akakata.app.PlayerSession;
import com.akakata.app.Session;
import com.akakata.app.impl.DefaultGame;
import com.akakata.app.impl.DefaultPlayerSession;
import com.akakata.security.SimpleCredentials;
import com.akakata.security.SimpleCredentialsVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies synchronous session-service shutdown, scheduler termination and rejection of later
 * registrations.
 */
class SessionServiceShutdownTest {

    /**
     * Creates a session service shutdown test instance.
     */
    SessionServiceShutdownTest() {
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
     * Sets the up.
     */
    @BeforeEach
    void setUp() {
        game = new DefaultGame();
        service = new SessionService(new SimpleCredentialsVerifier());
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
     * Releases the resources used by this test fixture.
     */
    @Test
    void closeSynchronouslyClosesCachedSessions() {
        AtomicReference<Thread> closedBy = new AtomicReference<>();
        PlayerSession session = new DefaultPlayerSession(new DefaultPlayerSession.PlayerSessionBuilder().game(game)) {
            @Override
            public synchronized void close() {
                closedBy.compareAndSet(null, Thread.currentThread());
                super.close();
            }
        };
        SimpleCredentials credentials = new SimpleCredentials();
        service.putSession(credentials, session);

        service.close();

        assertSame(Thread.currentThread(), closedBy.get(), "Shutdown must not rely on an async removal listener");
        assertEquals(Session.Status.CLOSED, session.getStatus());
        assertNull(service.getSession(credentials));
        assertEquals(0, service.size());
    }

    /**
     * Releases the resources used by this test fixture.
     *
     * @throws Exception if the operation fails
     */
    @Test
    void closeTerminatesCleanupExecutor() throws Exception {
        Field field = SessionService.class.getDeclaredField("cleanupExecutor");
        field.setAccessible(true);
        ScheduledExecutorService executor = (ScheduledExecutorService) field.get(service);

        service.close();

        assertTrue(executor.isTerminated());
    }

    /**
     * Releases the resources used by this test fixture.
     */
    @Test
    void closeIsIdempotent() {
        AtomicInteger closeCalls = new AtomicInteger();
        PlayerSession session = new DefaultPlayerSession(new DefaultPlayerSession.PlayerSessionBuilder().game(game)) {
            @Override
            public synchronized void close() {
                closeCalls.incrementAndGet();
                super.close();
            }
        };
        service.putSession(new SimpleCredentials(), session);

        service.close();
        service.close();

        assertEquals(1, closeCalls.get());
        assertEquals(0, service.size());
    }

    /**
     * Releases the resources used by this test fixture.
     */
    @Test
    void closedServiceRejectsNewSessions() {
        PlayerSession session = game.createPlayerSession();
        assertTrue(game.connectSession(session));
        SimpleCredentials credentials = new SimpleCredentials();
        service.close();

        assertFalse(service.putSession(credentials, session));
        assertThrows(IllegalStateException.class, () -> service.replaceSession(credentials, session));
        assertEquals(0, service.size());
    }

    /**
     * Releases the resources used by this test fixture.
     */
    @Test
    void closeContinuesAfterSessionFailure() {
        PlayerSession failing = new DefaultPlayerSession(new DefaultPlayerSession.PlayerSessionBuilder().game(game)) {
            @Override
            public synchronized void close() {
                throw new IllegalStateException("Injected session shutdown failure");
            }
        };
        PlayerSession healthy = game.createPlayerSession();
        service.putSession(new SimpleCredentials(), failing);
        service.putSession(new SimpleCredentials(), healthy);

        service.close();

        assertEquals(Session.Status.CLOSED, healthy.getStatus());
        assertEquals(0, service.size());
    }
}
