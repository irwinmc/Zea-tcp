package com.akakata.context.module;

import com.akakata.app.Game;
import com.akakata.app.impl.DefaultGame;
import com.akakata.context.ConfigurationManager;
import com.akakata.security.Credentials;
import com.akakata.service.SessionManagerService;
import com.akakata.service.TaskManagerService;
import com.akakata.service.impl.SimpleSessionManagerServiceImpl;
import com.akakata.service.impl.SimpleTaskManagerServiceImpl;
import com.google.inject.*;

/**
 * Provides game/session/task related services.
 */
public class ServiceModule extends AbstractModule {

    private final ConfigurationManager configurationManager;

    public ServiceModule(ConfigurationManager configurationManager) {
        this.configurationManager = configurationManager;
    }

    @Override
    protected void configure() {
        bind(new TypeLiteral<SessionManagerService<Credentials>>() {})
                .to(SimpleSessionManagerServiceImpl.class)
                .in(Scopes.SINGLETON);
        bind(Game.class).to(DefaultGame.class).in(Scopes.SINGLETON);
    }

    @Provides
    @Singleton
    SimpleTaskManagerServiceImpl provideTaskManagerServiceImpl() {
        int poolSize = configurationManager.getInt("taskManager.poolSize", 2);
        return new SimpleTaskManagerServiceImpl(poolSize);
    }

    @Provides
    @Singleton
    TaskManagerService provideTaskManagerService(SimpleTaskManagerServiceImpl impl) {
        return impl;
    }
}
