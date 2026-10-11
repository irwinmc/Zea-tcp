package com.akakata.context.module;

import com.akakata.app.Game;
import com.akakata.app.impl.DefaultGame;
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
     * @return the session service instance
     */
    @Provides
    @Singleton
    static SessionService provideSessionService() {
        return new SessionService(new SimpleCredentialsVerifier());
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
     * @return the game instance
     */
    @Provides
    @Singleton
    static Game provideGame() {
        return new DefaultGame();
    }
}
