package com.akakata.context.module;

import com.akakata.app.Game;
import com.akakata.context.ConfigurationManager;
import com.akakata.context.NetworkBootstrap;
import com.akakata.server.ServerManager;
import com.akakata.service.SessionService;
import dagger.BindsInstance;
import dagger.Component;
import javax.inject.Singleton;

/**
 * Exposes singleton application resources created by the Dagger dependency graph.
 */
@Singleton
@Component(modules = {
        ServiceModule.class,
        ProtocolModule.class,
        ServerModule.class
})
public interface GameServerComponent {

    /**
     * Returns the singleton server manager.
     *
     * @return the singleton server manager
     */
    ServerManager serverManager();

    /**
     * Returns the singleton game.
     *
     * @return the singleton game
     */
    Game game();

    /**
     * Returns the singleton session service.
     *
     * @return the singleton session service
     */
    SessionService sessionService();

    /**
     * Binds configuration and network resources before creating the application component.
     */
    @Component.Builder
    interface Builder {

        /**
         * Binds the configuration instance used by the component.
         *
         * @param configurationManager the loaded configuration manager
         * @return this component builder
         */
        @BindsInstance
        Builder configurationManager(ConfigurationManager configurationManager);

        /**
         * Binds the externally owned network resources used by server providers.
         *
         * @param networkBootstrap the owner of shared network event-loop groups
         * @return this component builder
         */
        @BindsInstance
        Builder networkBootstrap(NetworkBootstrap networkBootstrap);

        /**
         * Creates the dependency graph after both required instances have been bound.
         *
         * @return the constructed component
         */
        GameServerComponent build();
    }
}
