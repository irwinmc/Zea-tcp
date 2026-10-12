package com.akakata.context.module;

import com.akakata.app.Game;
import com.akakata.communication.OutboundBudget;
import com.akakata.communication.OutboundLimits;
import com.akakata.context.ConfigurationManager;
import com.akakata.context.ServerComponents;
import com.akakata.security.CredentialsVerifier;
import com.akakata.server.ConnectionAdmission;
import com.akakata.server.HttpLimits;
import com.akakata.server.TransportLimits;
import com.akakata.service.LoginService;
import com.akakata.service.SecurityService;
import com.akakata.service.SessionService;
import com.akakata.service.task.BlockingTaskExecutor;
import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;

/**
 * Provides the game, login service, credential verifier and session registry.
 *
 * @author Kelvin
 */
@Module
public final class ServiceModule {

    /**
     * Application-selected providers, with safe defaults for unspecified components.
     */
    private final ServerComponents components;

    /**
     * Creates default providers for a standalone Dagger component.
     */
    public ServiceModule() {
        this(ServerComponents.builder().build());
    }

    /**
     * Creates providers using the application's immutable component choices.
     *
     * @param components the selected application factories and services
     */
    public ServiceModule(ServerComponents components) {
        this.components = java.util.Objects.requireNonNull(components);
    }

    // ============================================================
    // Core Services
    // ============================================================

    /**
     * Provides the security service dependency for the application component.
     *
     * @param configuration the application properties
     * @return the security service instance
     */
    @Provides
    @Singleton
    SecurityService provideSecurityService(ConfigurationManager configuration) {
        return components.createSecurityService(configuration);
    }

    /**
     * Provides explicit authentication or the configured fail-closed default.
     *
     * @param configuration the application properties
     * @return the selected verifier
     */
    @Provides
    @Singleton
    CredentialsVerifier provideCredentialsVerifier(ConfigurationManager configuration) {
        return components.createCredentialsVerifier(configuration);
    }

    /**
     * Provides the session service dependency for the application component.
     *
     * @param configuration the application properties
     * @param verifier      the selected authentication implementation
     * @return the session service instance
     */
    @Provides
    @Singleton
    SessionService provideSessionService(ConfigurationManager configuration, CredentialsVerifier verifier) {
        return components.createSessionService(configuration, verifier);
    }

    /**
     * Provides per-connection application-write and encoded-buffer budgets.
     *
     * @param configuration the application properties
     * @return the validated socket outbound limits
     */
    @Provides
    @Singleton
    static OutboundLimits provideOutboundLimits(ConfigurationManager configuration) {
        return new OutboundLimits(
                configuration.getInt("socket.maximum.pending.writes", OutboundLimits.DEFAULT.maximumPendingWrites()),
                configuration.getInt("socket.maximum.pending.bytes", OutboundLimits.DEFAULT.maximumPendingBytes()),
                configuration.getLong("socket.maximum.pending.payload.bytes", OutboundLimits.DEFAULT.maximumPendingPayloadBytes()));
    }

    /**
     * Provides one aggregate source and encoded-byte owner for all application sockets.
     *
     * @param configuration the application properties
     * @return the context-wide reservation owner
     */
    @Provides
    @Singleton
    static OutboundBudget provideOutboundBudget(ConfigurationManager configuration) {
        return new OutboundBudget(configuration.getLong("socket.total.pending.bytes", OutboundBudget.DEFAULT_MAXIMUM_BYTES));
    }

    /**
     * Provides HTTP body and connection lifetime limits.
     *
     * @param configuration the application properties
     * @return the validated HTTP policy
     */
    @Provides
    @Singleton
    static HttpLimits provideHttpLimits(ConfigurationManager configuration) {
        return new HttpLimits(configuration.getInt("http.maximum.request.bytes", HttpLimits.DEFAULT.maximumRequestBytes()),
                configuration.getInt("http.request.timeout.millis", HttpLimits.DEFAULT.requestTimeoutMillis()),
                configuration.getInt("http.read.idle.millis", HttpLimits.DEFAULT.readIdleMillis()),
                configuration.getInt("http.maximum.connections", HttpLimits.DEFAULT.maximumConnections()));
    }

    /**
     * Provides the context-owned pool for detached blocking business operations.
     *
     * @param configuration the application properties
     * @return the bounded blocking-operation owner
     */
    @Provides
    @Singleton
    static BlockingTaskExecutor provideBlockingTaskExecutor(ConfigurationManager configuration) {
        return new BlockingTaskExecutor(configuration.getInt("business.blocking.threads", BlockingTaskExecutor.DEFAULT_THREADS),
                configuration.getInt("business.blocking.queue.capacity", BlockingTaskExecutor.DEFAULT_QUEUE_CAPACITY),
                configuration.getLong("business.blocking.queue.bytes", BlockingTaskExecutor.DEFAULT_QUEUE_BYTES),
                configuration.getLong("business.blocking.shutdown.millis", BlockingTaskExecutor.DEFAULT_SHUTDOWN_MILLIS));
    }

    /**
     * Provides one validated socket admission allowance shared by every listener.
     *
     * @param configuration the application properties
     * @return the shared connection owner
     */
    @Provides
    @Singleton
    static ConnectionAdmission provideConnectionAdmission(ConfigurationManager configuration) {
        var defaults = TransportLimits.DEFAULT;
        return new ConnectionAdmission(new TransportLimits(
                configuration.getInt("socket.maximum.connections", defaults.maximumConnections()),
                configuration.getInt("socket.login.timeout.millis", defaults.loginTimeoutMillis()),
                configuration.getInt("socket.read.idle.millis", defaults.readIdleMillis()),
                configuration.getInt("protocol.maximum.frame.bytes", defaults.maximumFrameBytes())));
    }

    /**
     * Provides the login service dependency for the application component.
     *
     * @param sessionService  the session registry and resource owner
     * @param securityService the token generation and verification service
     * @return the login service instance
     */
    @Provides
    @Singleton
    LoginService provideLoginService(SessionService sessionService, SecurityService securityService) {
        return components.createLoginService(sessionService, securityService);
    }

    /**
     * Provides the game dependency for the application component.
     *
     * @param configuration the application properties
     * @return the game instance
     */
    @Provides
    @Singleton
    Game provideGame(ConfigurationManager configuration) {
        return components.createGame(configuration);
    }
}
