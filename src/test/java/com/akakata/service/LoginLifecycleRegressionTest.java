package com.akakata.service;

import com.akakata.app.PlayerSession;
import com.akakata.app.Session;
import com.akakata.app.impl.DefaultGame;
import com.akakata.security.Credentials;
import com.akakata.security.SimpleCredentials;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies cleanup of failed login sessions while preserving valid prior sessions.
 */
class LoginLifecycleRegressionTest {

    /**
     * Creates a login lifecycle regression test instance.
     */
    LoginLifecycleRegressionTest() {
    }

    /**
     * Game used by this regression fixture.
     */
    private RecordingGame game;

    /**
     * Sessions used by this regression fixture.
     */
    private SessionService sessions;

    /**
     * Credentials used by this regression fixture.
     */
    private SimpleCredentials credentials;

    /**
     * Sets the up.
     */
    @BeforeEach
    void setUp() {
        game = new RecordingGame();
        credentials = new SimpleCredentials();
        sessions = new SessionService(buffer -> credentials, false);
    }

    /**
     * Releases the resources used by this test fixture.
     */
    @AfterEach
    void tearDown() {
        sessions.close();
        if (game.created != null) {
            game.created.close();
        }
        game.close();
    }

    /**
     * Verifies the regression scenario: token failure closes new session and keeps existing
     * session.
     */
    @Test
    void tokenFailureClosesNewSessionAndKeepsExistingSession() {
        PlayerSession previous = game.createPlayerSession();
        sessions.putSession(credentials, previous);
        var login = new LoginService(sessions, new SecurityService() {
            @Override
            public String generateToken(Credentials credentials) {
                throw new IllegalStateException("Injected token failure");
            }
        });

        assertNull(login.login(Unpooled.EMPTY_BUFFER, game));

        assertSame(previous, sessions.getSession(credentials));
        assertFalse(previous.isShuttingDown());
        assertEquals(Session.Status.CLOSED, game.created.getStatus());
    }

    /**
     * Releases the resources used by this test fixture.
     */
    @Test
    void closedServiceDoesNotLeakNewSessionOnLoginFailure() {
        sessions.close();
        var login = new LoginService(sessions, new SecurityService());

        assertNull(login.login(Unpooled.EMPTY_BUFFER, game));

        assertEquals(Session.Status.CLOSED, game.created.getStatus());
        assertEquals(0, sessions.size());
    }

    /**
     * Records sessions created during authentication regression scenarios.
     */
    private static class RecordingGame extends DefaultGame {

        /**
         * Creates a recording game instance.
         */
        private RecordingGame() {
        }

        /**
         * Created used by this regression fixture.
         */
        PlayerSession created;

        /**
         * {@inheritDoc}
         */
        @Override
        public PlayerSession createPlayerSession() {
            return created = super.createPlayerSession();
        }
    }
}
