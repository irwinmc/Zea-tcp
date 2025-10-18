package com.akakata.context.module;

import com.akakata.context.ConfigurationManager;
import com.akakata.context.NetworkBootstrap;
import com.akakata.server.ServerManager;
import dagger.BindsInstance;
import dagger.Component;
import javax.inject.Singleton;

/**
 * Dagger component for server infrastructure.
 * Only exposes ServerManager - individual servers are managed internally.
 */
@Singleton
@Component(modules = {
        ServiceModule.class,
        ProtocolModule.class,
        ServerModule.class
})
public interface GameServerComponent {

    /**
     * Get the server manager that controls all server instances.
     */
    ServerManager serverManager();

    @Component.Builder
    interface Builder {
        @BindsInstance
        Builder configurationManager(ConfigurationManager configurationManager);

        @BindsInstance
        Builder networkBootstrap(NetworkBootstrap networkBootstrap);

        GameServerComponent build();
    }
}
