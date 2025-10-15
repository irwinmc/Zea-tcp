package com.akakata.context.module;

import com.akakata.context.AppContext;
import com.akakata.context.ConfigurationManager;
import com.akakata.context.NetworkBootstrap;
import com.akakata.handlers.HttpRequestHandler;
import com.akakata.handlers.LoginHandler;
import com.akakata.handlers.WebSocketLoginHandler;
import com.akakata.server.NettyConfig;
import com.akakata.server.Server;
import com.akakata.server.ServerManager;
import com.akakata.server.http.ApiHandler;
import com.akakata.server.http.IndexPageHandler;
import com.akakata.server.http.RpcHandler;
import com.akakata.server.impl.NettyTCPServer;
import com.akakata.server.impl.ServerManagerImpl;
import com.akakata.server.initializer.HttpServerChannelInitializer;
import com.akakata.server.initializer.LoginChannelInitializer;
import com.akakata.server.initializer.WebSocketServerChannelInitializer;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.LengthFieldPrepender;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds Netty server instances and related channel initializers.
 */
public class ServerModule extends AbstractModule {

    private final ConfigurationManager configurationManager;

    public ServerModule(ConfigurationManager configurationManager) {
        this.configurationManager = configurationManager;
    }

    @Override
    protected void configure() {
        bind(ServerManager.class).to(ServerManagerImpl.class).in(Singleton.class);
    }

    @Provides
    @Singleton
    HttpRequestHandler provideHttpRequestHandler() {
        HttpRequestHandler handler = new HttpRequestHandler();
        handler.setIndexPageHandler(new IndexPageHandler());
        handler.setApiHandler(new ApiHandler());
        handler.setRpcHandler(new RpcHandler());
        return handler;
    }

    @Provides
    @Singleton
    LoginChannelInitializer provideLoginChannelInitializer(@Named("tcpDecoder") ChannelHandler decoder,
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
    HttpServerChannelInitializer provideHttpServerChannelInitializer(HttpRequestHandler handler) {
        HttpServerChannelInitializer initializer = new HttpServerChannelInitializer();
        initializer.setHttpRequestHandler(handler);
        return initializer;
    }

    @Provides
    @Singleton
    WebSocketServerChannelInitializer provideWebSocketServerChannelInitializer(WebSocketLoginHandler handler) {
        WebSocketServerChannelInitializer initializer = new WebSocketServerChannelInitializer();
        initializer.setWebSocketLoginHandler(handler);
        return initializer;
    }

    @Provides
    @Singleton
    @Named(AppContext.TCP_SERVER)
    Server provideTcpServer(LoginChannelInitializer initializer,
                            NetworkBootstrap networkBootstrap) {
        return createServer("tcp.port", 8090, initializer, networkBootstrap);
    }

    @Provides
    @Singleton
    @Named(AppContext.HTTP_SERVER)
    Server provideHttpServer(HttpServerChannelInitializer initializer,
                             NetworkBootstrap networkBootstrap) {
        return createServer("http.port", 8081, initializer, networkBootstrap);
    }

    @Provides
    @Singleton
    @Named(AppContext.WEB_SOCKET_SERVER)
    Server provideWebSocketServer(WebSocketServerChannelInitializer initializer,
                                  NetworkBootstrap networkBootstrap) {
        return createServer("web.socket.port", 8300, initializer, networkBootstrap);
    }

    private Server createServer(String key,
                                int defaultPort,
                                ChannelInitializer<? extends Channel> initializer,
                                NetworkBootstrap networkBootstrap) {
        NettyConfig config = new NettyConfig();
        config.setPortNumber(configurationManager.getInt(key, defaultPort));
        config.setBossGroup(networkBootstrap.getBossGroup());
        config.setWorkerGroup(networkBootstrap.getWorkerGroup());

        Map<ChannelOption<?>, Object> options = new HashMap<>();
        options.put(ChannelOption.SO_BACKLOG, configurationManager.getInt("so.backlog", 100));
        options.put(ChannelOption.SO_REUSEADDR, configurationManager.getBoolean("so.reuseaddr", true));
        config.setChannelOptions(options);

        Map<ChannelOption<?>, Object> childOps = new HashMap<>();
        childOps.put(ChannelOption.SO_KEEPALIVE, configurationManager.getBoolean("so.keepalive", true));
        childOps.put(ChannelOption.TCP_NODELAY, configurationManager.getBoolean("tcp.nodelay", true));
        config.setChannelChildOptions(childOps);

        return new NettyTCPServer(config, initializer);
    }
}
