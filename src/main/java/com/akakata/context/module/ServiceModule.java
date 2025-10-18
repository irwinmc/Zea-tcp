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
 * Dagger module for core service dependencies.
 * Provides session management, login service, and game instances.
 *
 * @author Kelvin
 */
@Module
public final class ServiceModule {

    private ServiceModule() {
    }

    // ============================================================
    // Core Services
    // ============================================================

    @Provides
    @Singleton
    static SecurityService provideSecurityService() {
        return new SecurityService();
    }

    @Provides
    @Singleton
    static SessionService provideSessionService() {
        return new SessionService(new SimpleCredentialsVerifier());
    }

    @Provides
    @Singleton
    static LoginService provideLoginService(SessionService sessionService, SecurityService securityService) {
        return new LoginService(sessionService, securityService);
    }

    @Provides
    @Singleton
    static Game provideGame() {
        return new DefaultGame();
    }
}
