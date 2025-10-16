package com.akakata.context.module;

import com.akakata.app.Game;
import com.akakata.app.impl.DefaultGame;
import com.akakata.context.ConfigurationManager;
import com.akakata.security.Credentials;
import com.akakata.service.SessionManagerService;
import com.akakata.service.TaskManagerService;
import com.akakata.service.impl.SimpleSessionManagerServiceImpl;
import com.akakata.service.impl.SimpleTaskManagerServiceImpl;
import dagger.Module;
import dagger.Provides;

import javax.inject.Singleton;

@Module
public final class ServiceModule {

    private ServiceModule() {
    }

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

    @Provides
    @Singleton
    static SimpleTaskManagerServiceImpl provideTaskManagerServiceImpl(ConfigurationManager configurationManager) {
        int poolSize = configurationManager.getInt("taskManager.poolSize", 2);
        return new SimpleTaskManagerServiceImpl(poolSize);
    }

    @Provides
    @Singleton
    static TaskManagerService provideTaskManagerService(SimpleTaskManagerServiceImpl impl) {
        return impl;
    }
}
