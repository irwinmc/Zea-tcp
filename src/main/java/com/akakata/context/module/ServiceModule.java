package com.akakata.context.module;

import com.akakata.app.Game;
import com.akakata.app.impl.DefaultGame;
import com.akakata.context.ConfigurationManager;
import com.akakata.communication.OutboundLimits;
import com.akakata.security.SimpleCredentialsVerifier;
import com.akakata.service.LoginService;
import com.akakata.service.SecurityService;
import com.akakata.service.SessionService;
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
     * Prevents direct construction of this utility or singleton component.
     */
    private ServiceModule() {
    }

    // ============================================================
    // Core Services
    // ============================================================

    /**
     * Provides the security service dependency for the application component.
     *
     * @return the security service instance
     */
    @Provides
    @Singleton
    static SecurityService provideSecurityService() {
        return new SecurityService();
    }

    /**
     * Provides the session service dependency for the application component.
     *
     * @param configuration the application properties
     * @return the session service instance
     */
    @Provides
    @Singleton
    static SessionService provideSessionService(ConfigurationManager configuration) {
        return new SessionService(new SimpleCredentialsVerifier(), true,
                configuration.getInt("session.maximum", SessionService.DEFAULT_MAXIMUM_SESSIONS));
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
                configuration.getInt("socket.maximum.pending.bytes", OutboundLimits.DEFAULT.maximumPendingBytes()));
    }

    /**
     * Provides the login service dependency for the application component.
     *
     * @param sessionService the session registry and resource owner
     * @param securityService the token generation and verification service
     * @return the login service instance
     */
    @Provides
    @Singleton
    static LoginService provideLoginService(SessionService sessionService, SecurityService securityService) {
        return new LoginService(sessionService, securityService);
    }

    /**
     * Provides the game dependency for the application component.
     *
     * @param configuration the application properties
     * @return the game instance
     */
    @Provides
    @Singleton
    static Game provideGame(ConfigurationManager configuration) {
        return new DefaultGame(configuration.getInt("game.queue.capacity",
                com.akakata.event.impl.NettyEventDispatcher.DEFAULT_QUEUE_CAPACITY));
    }
}
