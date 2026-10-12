package com.akakata.context.module;

import com.akakata.app.Game;
import com.akakata.context.ConfigurationManager;
import com.akakata.context.NetworkBootstrap;
import com.akakata.server.ServerManager;
import com.akakata.service.SessionService;
import com.akakata.service.LoginService;
import com.akakata.service.SecurityService;
import com.akakata.security.CredentialsVerifier;
import com.akakata.communication.OutboundBudget;
import com.akakata.service.task.BlockingTaskExecutor;
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
     * Returns the selected credential verifier.
     *
     * @return the singleton authentication implementation
     */
    CredentialsVerifier credentialsVerifier();

    /**
     * Returns the selected token service.
     *
     * @return the singleton token implementation
     */
    SecurityService securityService();

    /**
     * Returns the selected authentication coordinator.
     *
     * @return the singleton login service
     */
    LoginService loginService();

    /**
     * Returns the aggregate write reservation owner.
     *
     * @return the context's outbound budget
     */
    OutboundBudget outboundBudget();

    /**
     * Returns the owned blocking-operation pool.
     *
     * @return the bounded task executor
     */
    BlockingTaskExecutor blockingTaskExecutor();

    /**
     * Binds configuration and network resources before creating the application component.
     */
    @Component.Builder
    interface Builder {

        /**
         * Selects application provider replacements before building the graph.
         *
         * @param module the configured core providers
         * @return this builder
         */
        Builder serviceModule(ServiceModule module);

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
