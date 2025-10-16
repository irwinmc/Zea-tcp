package com.akakata.context.module;

import com.akakata.app.Game;
import com.akakata.app.impl.DefaultGame;
import com.akakata.security.Credentials;
import com.akakata.service.SessionManagerService;
import com.akakata.service.impl.SimpleSessionManagerServiceImpl;
import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;

/**
 * Dagger module for core service dependencies.
 * Provides session management and game instances.
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
    static SessionManagerService<Credentials> provideSessionManagerService() {
        return new SimpleSessionManagerServiceImpl();
    }

    @Provides
    @Singleton
    static Game provideGame() {
        return new DefaultGame();
    }
}
