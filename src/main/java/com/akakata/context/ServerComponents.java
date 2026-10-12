package com.akakata.context;

import com.akakata.app.Game;
import com.akakata.app.impl.DefaultGame;
import com.akakata.event.impl.NettyEventDispatcher;
import com.akakata.security.CredentialsVerifier;
import com.akakata.security.SimpleCredentialsVerifier;
import com.akakata.service.LoginService;
import com.akakata.service.SecurityService;
import com.akakata.service.SessionService;

import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Supplies application replacements for the core Dagger providers.
 *
 * <p>Properties still supply configuration values. Factories supply implementations
 * and run lazily during context initialization. Each factory must return a nonnull
 * resource. A context owns and closes its resulting Game and SessionService, including
 * application replacements; do not share them between contexts. Verifiers and token
 * services remain application-owned and must not own context lifecycle resources.
 */
public final class ServerComponents {

    /**
     * Application Game factory, or null for the configured default.
     */
    private final Function<ConfigurationManager, Game> gameFactory;

    /**
     * Explicit authentication implementation, or null for the configured policy.
     */
    private final CredentialsVerifier verifier;

    /**
     * Explicit token implementation, or null for the configured AES-GCM service.
     */
    private final SecurityService security;

    /**
     * Application session-registry factory, or null for the bounded default.
     */
    private final BiFunction<ConfigurationManager, CredentialsVerifier, SessionService> sessionFactory;

    /**
     * Application login coordinator factory, or null for the default coordinator.
     */
    private final BiFunction<SessionService, SecurityService, LoginService> loginFactory;

    /**
     * Captures an immutable set of provider choices.
     *
     * @param builder the completed application choices
     */
    private ServerComponents(Builder builder) {
        gameFactory = builder.gameFactory;
        verifier = builder.verifier;
        security = builder.security;
        sessionFactory = builder.sessionFactory;
        loginFactory = builder.loginFactory;
    }

    /**
     * Creates a builder whose unspecified components use the framework defaults.
     *
     * @return a new application component builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates the Game owned by a context.
     *
     * @param configuration the loaded application properties
     * @return the application Game
     */
    public Game createGame(ConfigurationManager configuration) {
        return Objects.requireNonNull(gameFactory == null
                ? new DefaultGame(configuration.getInt("game.queue.capacity", NettyEventDispatcher.DEFAULT_QUEUE_CAPACITY),
                        configuration.getLong("game.queue.bytes", NettyEventDispatcher.DEFAULT_QUEUE_BYTES))
                : gameFactory.apply(configuration), "Game factory returned null");
    }

    /**
     * Selects explicit authentication or a default policy that denies login.
     *
     * <p>Anonymous demonstration credentials require an explicit
     * {@code security.allow.anonymous=true} setting. They never authenticate an account.
     *
     * @param configuration the loaded application properties
     * @return the configured credential verifier
     */
    public CredentialsVerifier createCredentialsVerifier(ConfigurationManager configuration) {
        if (verifier != null) {
            return verifier;
        }
        return configuration.getBoolean("security.allow.anonymous", false)
                ? new SimpleCredentialsVerifier() : ignored -> null;
    }

    /**
     * Selects the application token service or builds the configured secure default.
     *
     * @param configuration the loaded application properties
     * @return the token service
     */
    public SecurityService createSecurityService(ConfigurationManager configuration) {
        return security == null ? SecurityService.fromConfiguration(configuration) : security;
    }

    /**
     * Creates the session registry owned by a context.
     *
     * @param configuration the loaded application properties
     * @param credentialsVerifier the selected authentication implementation
     * @return the session registry
     */
    public SessionService createSessionService(ConfigurationManager configuration, CredentialsVerifier credentialsVerifier) {
        return Objects.requireNonNull(sessionFactory == null
                ? new SessionService(credentialsVerifier, true,
                        configuration.getInt("session.maximum", SessionService.DEFAULT_MAXIMUM_SESSIONS))
                : sessionFactory.apply(configuration, credentialsVerifier), "SessionService factory returned null");
    }

    /**
     * Creates the authentication coordinator using the selected singleton services.
     *
     * @param sessions the owned session registry
     * @param tokens the token service
     * @return the login coordinator
     */
    public LoginService createLoginService(SessionService sessions, SecurityService tokens) {
        return Objects.requireNonNull(loginFactory == null ? new LoginService(sessions, tokens)
                : loginFactory.apply(sessions, tokens), "LoginService factory returned null");
    }

    /**
     * Collects application provider replacements before constructing a context.
     */
    public static final class Builder {

        /**
         * Configured Game factory.
         */
        private Function<ConfigurationManager, Game> gameFactory;

        /**
         * Configured credential verifier.
         */
        private CredentialsVerifier verifier;

        /**
         * Configured token service.
         */
        private SecurityService security;

        /**
         * Configured session-registry factory.
         */
        private BiFunction<ConfigurationManager, CredentialsVerifier, SessionService> sessionFactory;

        /**
         * Configured authentication coordinator factory.
         */
        private BiFunction<SessionService, SecurityService, LoginService> loginFactory;

        /**
         * Creates an initially default provider set.
         */
        private Builder() {
        }

        /**
         * Replaces the Game provider, preserving context ownership of the result.
         *
         * @param factory the application Game factory
         * @return this builder
         */
        public Builder gameFactory(Function<ConfigurationManager, Game> factory) {
            gameFactory = Objects.requireNonNull(factory);
            return this;
        }

        /**
         * Replaces the credential verifier used by the session and login services.
         *
         * @param credentialsVerifier the application's account authenticator
         * @return this builder
         */
        public Builder credentialsVerifier(CredentialsVerifier credentialsVerifier) {
            verifier = Objects.requireNonNull(credentialsVerifier);
            return this;
        }

        /**
         * Replaces the token service without reading the default token configuration.
         *
         * @param service the application's token implementation
         * @return this builder
         */
        public Builder securityService(SecurityService service) {
            security = Objects.requireNonNull(service);
            return this;
        }

        /**
         * Replaces the registry provider, preserving context ownership of its result.
         *
         * @param factory the factory receiving configuration and the selected verifier
         * @return this builder
         */
        public Builder sessionServiceFactory(BiFunction<ConfigurationManager, CredentialsVerifier, SessionService> factory) {
            sessionFactory = Objects.requireNonNull(factory);
            return this;
        }

        /**
         * Replaces the authentication coordinator using the selected singleton services.
         *
         * @param factory the application login coordinator factory
         * @return this builder
         */
        public Builder loginServiceFactory(BiFunction<SessionService, SecurityService, LoginService> factory) {
            loginFactory = Objects.requireNonNull(factory);
            return this;
        }

        /**
         * Captures the provider choices independently of subsequent builder changes.
         *
         * @return immutable provider choices
         */
        public ServerComponents build() {
            return new ServerComponents(this);
        }
    }
}
