package com.akakata.context.module;

import com.akakata.context.ConfigurationManager;
import com.akakata.context.NetworkBootstrap;
import com.google.inject.AbstractModule;

/**
 * Aggregates all Guice modules required to bootstrap the game server.
 */
public class GameServerModule extends AbstractModule {

    private final ConfigurationManager configurationManager;
    private final NetworkBootstrap networkBootstrap;

    public GameServerModule(ConfigurationManager configurationManager,
                            NetworkBootstrap networkBootstrap) {
        this.configurationManager = configurationManager;
        this.networkBootstrap = networkBootstrap;
    }

    @Override
    protected void configure() {
        install(new InfrastructureModule(configurationManager, networkBootstrap));
        install(new ServiceModule(configurationManager));
        install(new ProtocolModule(configurationManager));
        install(new ServerModule(configurationManager));
    }
}
