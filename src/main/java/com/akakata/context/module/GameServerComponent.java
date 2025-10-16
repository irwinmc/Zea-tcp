package com.akakata.context.module;

import com.akakata.context.ConfigurationManager;
import com.akakata.context.NetworkBootstrap;
import com.akakata.server.Server;
import dagger.BindsInstance;
import dagger.Component;
import javax.inject.Named;
import javax.inject.Singleton;

import static com.akakata.context.AppContext.HTTP_SERVER;
import static com.akakata.context.AppContext.TCP_SERVER;
import static com.akakata.context.AppContext.WEB_SOCKET_SERVER;

@Singleton
@Component(modules = {
        ServiceModule.class,
        ProtocolModule.class,
        ServerModule.class
})
public interface GameServerComponent {

    @Named(TCP_SERVER)
    Server tcpServer();

    @Named(HTTP_SERVER)
    Server httpServer();

    @Named(WEB_SOCKET_SERVER)
    Server webSocketServer();

    @Component.Builder
    interface Builder {
        @BindsInstance
        Builder configurationManager(ConfigurationManager configurationManager);

        @BindsInstance
        Builder networkBootstrap(NetworkBootstrap networkBootstrap);

        GameServerComponent build();
    }
}
