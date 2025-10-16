package com.akakata.context.module;

import com.akakata.context.AppContext;
import com.akakata.context.ConfigurationManager;
import com.akakata.context.NetworkBootstrap;
import com.akakata.handlers.HttpRequestHandler;
import com.akakata.handlers.LoginHandler;
import com.akakata.handlers.WebSocketLoginHandler;
import com.akakata.protocols.Protocol;
import com.akakata.server.NettyConfig;
import com.akakata.server.Server;
import com.akakata.server.ServerManager;
import com.akakata.server.impl.NettyTCPServer;
import com.akakata.server.impl.ServerManagerImpl;
import com.akakata.server.initializer.HttpServerChannelInitializer;
import com.akakata.server.initializer.LoginChannelInitializer;
import com.akakata.server.initializer.WebSocketServerChannelInitializer;
import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.LengthFieldPrepender;

import javax.inject.Named;
import javax.inject.Singleton;

import java.util.HashMap;
import java.util.Map;

@Module
public abstract class ServerModule {

    private ServerModule() {
    }

    @Binds
    @Singleton
    abstract ServerManager bindServerManager(ServerManagerImpl impl);

    @Provides
    @Singleton
    static HttpRequestHandler provideHttpRequestHandler() {
        HttpRequestHandler handler = new HttpRequestHandler();
        handler.setIndexPageHandler(new com.akakata.server.http.IndexPageHandler());
        handler.setApiHandler(new com.akakata.server.http.ApiHandler());
        handler.setRpcHandler(new com.akakata.server.http.RpcHandler());
        return handler;
    }

    @Provides
    @Singleton
    static LoginChannelInitializer provideLoginChannelInitializer(@Named("tcpDecoder") ChannelHandler decoder,
                                                                  LoginHandler loginHandler,
                                                                  LengthFieldPrepender prepender) {
        LoginChannelInitializer initializer = new LoginChannelInitializer();
        initializer.setEventDecoder(decoder);
        initializer.setLoginHandler(loginHandler);
        initializer.setLengthFieldPrepender(prepender);
        return initializer;
    }

    @Provides
    @Singleton
    static HttpServerChannelInitializer provideHttpServerChannelInitializer(HttpRequestHandler handler) {
        HttpServerChannelInitializer initializer = new HttpServerChannelInitializer();
        initializer.setHttpRequestHandler(handler);
        return initializer;
    }

    @Provides
    @Singleton
    static WebSocketServerChannelInitializer provideWebSocketServerChannelInitializer(WebSocketLoginHandler handler) {
        WebSocketServerChannelInitializer initializer = new WebSocketServerChannelInitializer();
        initializer.setWebSocketLoginHandler(handler);
        return initializer;
    }

    @Provides
    @Singleton
    @Named(AppContext.TCP_SERVER)
    static Server provideTcpServer(ConfigurationManager configurationManager,
                                   NetworkBootstrap networkBootstrap,
                                   LoginChannelInitializer initializer) {
        return createServer(configurationManager, networkBootstrap, "tcp.port", 8090, initializer);
    }

    @Provides
    @Singleton
    @Named(AppContext.HTTP_SERVER)
    static Server provideHttpServer(ConfigurationManager configurationManager,
                                    NetworkBootstrap networkBootstrap,
                                    HttpServerChannelInitializer initializer) {
        return createServer(configurationManager, networkBootstrap, "http.port", 8081, initializer);
    }

    @Provides
    @Singleton
    @Named(AppContext.WEB_SOCKET_SERVER)
    static Server provideWebSocketServer(ConfigurationManager configurationManager,
                                         NetworkBootstrap networkBootstrap,
                                         WebSocketServerChannelInitializer initializer) {
        return createServer(configurationManager, networkBootstrap, "web.socket.port", 8300, initializer);
    }

    private static Server createServer(ConfigurationManager configurationManager,
                                        NetworkBootstrap networkBootstrap,
                                        String portKey,
                                        int defaultPort,
                                        ChannelInitializer<? extends Channel> initializer) {
        NettyConfig config = new NettyConfig();
        config.setPortNumber(configurationManager.getInt(portKey, defaultPort));
        config.setBossGroup(networkBootstrap.getBossGroup());
        config.setWorkerGroup(networkBootstrap.getWorkerGroup());

        Map<ChannelOption<?>, Object> options = new HashMap<>();
        options.put(ChannelOption.SO_BACKLOG, configurationManager.getInt("so.backlog", 100));
        options.put(ChannelOption.SO_REUSEADDR, configurationManager.getBoolean("so.reuseaddr", true));
        config.setChannelOptions(options);

        Map<ChannelOption<?>, Object> childOptions = new HashMap<>();
        childOptions.put(ChannelOption.SO_KEEPALIVE, configurationManager.getBoolean("so.keepalive", true));
        childOptions.put(ChannelOption.TCP_NODELAY, configurationManager.getBoolean("tcp.nodelay", true));
        config.setChannelChildOptions(childOptions);

        return new NettyTCPServer(config, initializer);
    }
}
