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
import com.akakata.server.http.ApiHandler;
import com.akakata.server.http.IndexPageHandler;
import com.akakata.server.http.RpcHandler;
import com.akakata.server.http.StaticFileHandler;
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

/**
 * Dagger module for server-related dependencies.
 * Provides Server instances, HTTP handlers, and channel initializers.
 *
 * @author Kelvin
 */
@Module
public abstract class ServerModule {

    private ServerModule() {
    }

    // ============================================================
    // ServerManager Binding
    // ============================================================

    @Binds
    @Singleton
    abstract ServerManager bindServerManager(ServerManagerImpl impl);

    // ============================================================
    // HTTP Handlers
    // ============================================================

    @Provides
    @Singleton
    static StaticFileHandler provideStaticFileHandler() {
        return new StaticFileHandler();
    }

    @Provides
    @Singleton
    static IndexPageHandler provideIndexPageHandler() {
        return new IndexPageHandler();
    }

    @Provides
    @Singleton
    static ApiHandler provideApiHandler() {
        return new ApiHandler();
    }

    @Provides
    @Singleton
    static RpcHandler provideRpcHandler() {
        return new RpcHandler();
    }

    @Provides
    @Singleton
    static HttpRequestHandler provideHttpRequestHandler(StaticFileHandler staticFileHandler,
                                                         IndexPageHandler indexPageHandler,
                                                         ApiHandler apiHandler,
                                                         RpcHandler rpcHandler) {
        return new HttpRequestHandler(staticFileHandler, indexPageHandler, apiHandler, rpcHandler);
    }

    // ============================================================
    // Channel Initializers
    // ============================================================

    @Provides
    @Singleton
    static LoginChannelInitializer provideLoginChannelInitializer(@Named("tcpDecoder") ChannelHandler decoder,
                                                                  LoginHandler loginHandler,
                                                                  LengthFieldPrepender prepender) {
        return new LoginChannelInitializer(decoder, loginHandler, prepender);
    }

    @Provides
    @Singleton
    static HttpServerChannelInitializer provideHttpServerChannelInitializer(HttpRequestHandler handler) {
        return new HttpServerChannelInitializer(handler);
    }

    @Provides
    @Singleton
    static WebSocketServerChannelInitializer provideWebSocketServerChannelInitializer(WebSocketLoginHandler handler) {
        return new WebSocketServerChannelInitializer(handler);
    }

    // ============================================================
    // Server Providers
    // ============================================================

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

    // ============================================================
    // Helper Methods
    // ============================================================

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
