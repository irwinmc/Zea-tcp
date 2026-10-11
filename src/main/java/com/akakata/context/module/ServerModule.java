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
import com.akakata.server.http.*;
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
 * Binds the server manager and provides server instances, HTTP handlers and channel initializers.
 *
 * @author Kelvin
 */
@Module
public abstract class ServerModule {

    /**
     * Prevents direct construction of this utility or singleton component.
     */
    private ServerModule() {
    }

    // ============================================================
    // ServerManager Binding
    // ============================================================

    /**
     * Binds the server-manager implementation to the lifecycle interface.
     *
     * @param impl the impl
     * @return the supplied server-manager implementation
     */
    @Binds
    @Singleton
    abstract ServerManager bindServerManager(ServerManagerImpl impl);

    // ============================================================
    // HTTP Handlers
    // ============================================================

    /**
     * Provides the static file handler dependency for the application component.
     *
     * @return the static file handler instance
     */
    @Provides
    @Singleton
    static StaticFileHandler provideStaticFileHandler() {
        return new StaticFileHandler();
    }

    /**
     * Provides the index page handler dependency for the application component.
     *
     * @return the index page handler instance
     */
    @Provides
    @Singleton
    static IndexPageHandler provideIndexPageHandler() {
        return new IndexPageHandler();
    }

    /**
     * Provides the api handler dependency for the application component.
     *
     * @return the api handler instance
     */
    @Provides
    @Singleton
    static ApiHandler provideApiHandler() {
        return new ApiHandler();
    }

    /**
     * Provides the rpc handler dependency for the application component.
     *
     * @return the rpc handler instance
     */
    @Provides
    @Singleton
    static RpcHandler provideRpcHandler() {
        return new RpcHandler();
    }

    /**
     * Provides the health check handler dependency for the application component.
     *
     * @return the health check handler instance
     */
    @Provides
    @Singleton
    static HealthCheckHandler provideHealthCheckHandler() {
        return new HealthCheckHandler();
    }

    /**
     * Provides the metrics handler dependency for the application component.
     *
     * @return the metrics handler instance
     */
    @Provides
    @Singleton
    static MetricsHandler provideMetricsHandler() {
        return new MetricsHandler();
    }

    /**
     * Provides the http request handler dependency for the application component.
     *
     * @param staticFileHandler the static file handler
     * @param indexPageHandler the index page handler
     * @param apiHandler the api handler
     * @param rpcHandler the rpc handler
     * @param healthCheckHandler the health check handler
     * @param metricsHandler the metrics handler
     * @return the http request handler instance
     */
    @Provides
    @Singleton
    static HttpRequestHandler provideHttpRequestHandler(StaticFileHandler staticFileHandler,
                                                        IndexPageHandler indexPageHandler,
                                                        ApiHandler apiHandler,
                                                        RpcHandler rpcHandler,
                                                        HealthCheckHandler healthCheckHandler,
                                                        MetricsHandler metricsHandler) {
        return new HttpRequestHandler(staticFileHandler, indexPageHandler, apiHandler, rpcHandler,
                healthCheckHandler, metricsHandler);
    }

    // ============================================================
    // Channel Initializers
    // ============================================================

    /**
     * Provides the login channel initializer dependency for the application component.
     *
     * @param decoder the inbound decoder or object converter
     * @param loginHandler the login handler
     * @param prepender the prepender
     * @return the login channel initializer instance
     */
    @Provides
    @Singleton
    static LoginChannelInitializer provideLoginChannelInitializer(@Named("tcpDecoder") ChannelHandler decoder,
                                                                  LoginHandler loginHandler,
                                                                  LengthFieldPrepender prepender) {
        return new LoginChannelInitializer(decoder, loginHandler, prepender);
    }

    /**
     * Provides the http server channel initializer dependency for the application component.
     *
     * @param handler the handler or request router
     * @return the http server channel initializer instance
     */
    @Provides
    @Singleton
    static HttpServerChannelInitializer provideHttpServerChannelInitializer(HttpRequestHandler handler) {
        return new HttpServerChannelInitializer(handler);
    }

    /**
     * Provides the web socket server channel initializer dependency for the application component.
     *
     * @param handler the handler or request router
     * @return the web socket server channel initializer instance
     */
    @Provides
    @Singleton
    static WebSocketServerChannelInitializer provideWebSocketServerChannelInitializer(WebSocketLoginHandler handler) {
        return new WebSocketServerChannelInitializer(handler);
    }

    // ============================================================
    // Server Providers
    // ============================================================

    /**
     * Provides the tcp server dependency for the application component.
     *
     * @param configurationManager the loaded configuration manager
     * @param networkBootstrap the owner of shared network event-loop groups
     * @param initializer the initializer for accepted channels
     * @return the tcp server instance
     */
    @Provides
    @Singleton
    @Named(AppContext.TCP_SERVER)
    static Server provideTcpServer(ConfigurationManager configurationManager,
                                   NetworkBootstrap networkBootstrap,
                                   LoginChannelInitializer initializer) {
        return createServer(configurationManager, networkBootstrap, "tcp.port", 8090, initializer);
    }

    /**
     * Provides the http server dependency for the application component.
     *
     * @param configurationManager the loaded configuration manager
     * @param networkBootstrap the owner of shared network event-loop groups
     * @param initializer the initializer for accepted channels
     * @return the http server instance
     */
    @Provides
    @Singleton
    @Named(AppContext.HTTP_SERVER)
    static Server provideHttpServer(ConfigurationManager configurationManager,
                                    NetworkBootstrap networkBootstrap,
                                    HttpServerChannelInitializer initializer) {
        return createServer(configurationManager, networkBootstrap, "http.port", 8081, initializer);
    }

    /**
     * Provides the web socket server dependency for the application component.
     *
     * @param configurationManager the loaded configuration manager
     * @param networkBootstrap the owner of shared network event-loop groups
     * @param initializer the initializer for accepted channels
     * @return the web socket server instance
     */
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

    /**
     * Creates a server with configured ports and socket options using shared network groups.
     *
     * @param configurationManager the loaded configuration manager
     * @param networkBootstrap the owner of shared network event-loop groups
     * @param portKey the configuration key for the bind port
     * @param defaultPort the fallback bind port
     * @param initializer the initializer for accepted channels
     * @return the configured server
     */
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
