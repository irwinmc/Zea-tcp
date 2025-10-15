package com.akakata.context.module;

import com.akakata.context.ConfigurationManager;
import com.akakata.context.NetworkBootstrap;
import com.google.inject.AbstractModule;

/**
 * Binds shared infrastructure singletons (configuration, Netty bootstrap).
 */
public class InfrastructureModule extends AbstractModule {

    private final ConfigurationManager configurationManager;
    private final NetworkBootstrap networkBootstrap;

    public InfrastructureModule(ConfigurationManager configurationManager,
                                NetworkBootstrap networkBootstrap) {
        this.configurationManager = configurationManager;
        this.networkBootstrap = networkBootstrap;
    }

    @Override
    protected void configure() {
        bind(ConfigurationManager.class).toInstance(configurationManager);
        bind(NetworkBootstrap.class).toInstance(networkBootstrap);
    }
}
