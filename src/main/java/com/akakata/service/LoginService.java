package com.akakata.service;

import com.akakata.app.Game;
import com.akakata.app.PlayerSession;
import com.akakata.security.Credentials;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Objects;

/**
 * Coordinates credential verification, player-session creation, token generation and session
 * replacement.
 *
 * <p>A token is generated before publishing a replacement. Failures release the
 * new session, and token failures leave an existing cached session in place.
 *
 * @author Kelvin
 * @since 0.7.8
 */
public class LoginService {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(LoginService.class);

    /**
     * Returns an authenticated player session and its encrypted token.
     *
     * @param session the authenticated or targeted session
     * @param token   the encrypted credential token
     */
    public record LoginResult(PlayerSession session, String token) {

        /**
         * Validates that the authenticated session and token are both nonnull.
         *
         * @param session the session associated with the operation
         * @param token   the encrypted login token
         * @throws NullPointerException if the session or token is null
         */
        public LoginResult {
            Objects.requireNonNull(session, "session cannot be null");
            Objects.requireNonNull(token, "token cannot be null");
        }
    }

    /**
     * Credential-keyed session registry and session resource owner.
     */
    private final SessionService sessionService;

    /**
     * Service for encrypting and verifying credential tokens.
     */
    private final SecurityService securityService;

    /**
     * Creates the authentication coordinator using the session and token services.
     *
     * @param sessionService  the session registry and resource owner
     * @param securityService the token generation and verification service
     */
    @Inject
    public LoginService(SessionService sessionService, SecurityService securityService) {
        this.sessionService = sessionService;
        this.securityService = securityService;
        LOG.info("LoginService initialized");
    }

    /**
     * Authenticates a login, creates a session and publishes it after token generation.
     *
     * <p>Replacing an existing session closes it synchronously. Failures release the
     * new session and return null. The input buffer remains owned by the caller.
     * Full registry capacity rejects new credentials without removing an existing player.
     *
     * @param buffer the source native buffer
     * @param game   the associated game
     * @return the authenticated session and token, or {@code null} if login fails
     */
    public LoginResult login(ByteBuf buffer, Game game) {
        PlayerSession newSession = null;
        try {
            // 1. 验证凭证
            Credentials credentials = verify(buffer);
            if (credentials == null) {
                LOG.warn("Login failed: invalid credentials");
                return null;
            }

            // 2. 创建新会话
            newSession = createSession(credentials, game);
            if (newSession == null || newSession.getId() == null) {
                LOG.error("Login failed: session creation returned null or missing ID, credentials={}", credentials);
                if (newSession != null) {
                    newSession.close();
                }
                return null;
            }

            // Generate and validate the response before kicking an existing connection.
            String token = Objects.requireNonNull(securityService.generateToken(credentials), "token cannot be null");

            // 3. 替换旧会话
            PlayerSession oldSession = sessionService.replaceSession(credentials, newSession);
            if (oldSession != null) {
                LOG.warn("Kicked old session: credentials={}, oldSessionId={}, newSessionId={}",
                        credentials,
                        Objects.toString(oldSession.getId(), "N/A"),
                        newSession.getId());
            }

            LOG.info("Login successful: sessionId={}, credentials={}", newSession.getId(), credentials);
            return new LoginResult(newSession, token);
        } catch (SessionCapacityException full) {
            if (newSession != null) {
                try {
                    newSession.close();
                } catch (Exception cleanupFailure) {
                    LOG.warn("Failed to close a capacity-rejected session", cleanupFailure);
                }
            }
            LOG.info("Login rejected because session capacity is exhausted");
            return null;
        } catch (Exception e) {
            if (newSession != null) {
                try {
                    newSession.close();
                } catch (Exception closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
            LOG.error("Login failed due to exception", e);
            return null;
        }
    }

    /**
     * Delegates credential verification and logs failures without taking buffer ownership.
     *
     * @param buffer the source native buffer
     * @return verified credentials, or {@code null} if verification fails
     */
    public Credentials verify(ByteBuf buffer) {
        try {
            Credentials credentials = sessionService.verify(buffer);
            if (credentials == null) {
                LOG.warn("Invalid credentials: buffer is empty or token is blank");
            }
            return credentials;
        } catch (Exception e) {
            LOG.error("Failed to verify credentials", e);
            return null;
        }
    }

    /**
     * Creates a game session and stores its credentials attribute. Closes a partially created
     * session on failure.
     *
     * @param credentials the verified credential identity
     * @param game        the associated game
     * @return the new session, or {@code null} if creation fails
     */
    private PlayerSession createSession(Credentials credentials, Game game) {
        PlayerSession newSession = null;
        try {
            // 创建新会话
            newSession = game.createPlayerSession();
            newSession.setAttribute("credentials", credentials);

            LOG.info("Session created: credentials={}, sessionId={}", credentials, newSession.getId());
            return newSession;
        } catch (Exception e) {
            if (newSession != null) {
                try {
                    newSession.close();
                } catch (Exception closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
            LOG.error("Failed to create session for credentials: {}", credentials, e);
            return null;
        }
    }

    /**
     * Returns the session cache's cumulative statistics.
     *
     * @return the cache statistics snapshot
     */
    public CacheStats getSessionStats() {
        return sessionService.getStats();
    }

    /**
     * Returns the current number of registered credential slots.
     *
     * @return the current credential registry size
     */
    public long getSessionCount() {
        return sessionService.size();
    }
}
