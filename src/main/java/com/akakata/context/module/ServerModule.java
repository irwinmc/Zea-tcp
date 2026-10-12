package com.akakata.context.module;

import com.akakata.communication.OutboundBudget;
import com.akakata.communication.OutboundLimits;
import com.akakata.context.AppContext;
import com.akakata.context.ConfigurationManager;
import com.akakata.context.NetworkBootstrap;
import com.akakata.handlers.HttpRequestHandler;
import com.akakata.handlers.LoginHandler;
import com.akakata.handlers.WebSocketLoginHandler;
import com.akakata.server.*;
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
import java.net.InetSocketAddress;
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
     * @param staticFileHandler  the static file handler
     * @param indexPageHandler   the index page handler
     * @param apiHandler         the api handler
     * @param rpcHandler         the rpc handler
     * @param healthCheckHandler the health check handler
     * @param metricsHandler     the metrics handler
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
     * @param decoder      the inbound decoder or object converter
     * @param loginHandler the login handler
     * @param prepender    the prepender
     * @param admission    the shared TCP/WebSocket admission policy
     * @param limits       the per-channel write allowance
     * @param budget       the shared write reservation owner
     * @return the login channel initializer instance
     */
    @Provides
    @Singleton
    static LoginChannelInitializer provideLoginChannelInitializer(@Named("tcpDecoder") ChannelHandler decoder,
                                                                  LoginHandler loginHandler,
                                                                  LengthFieldPrepender prepender, ConnectionAdmission admission,
                                                                  OutboundLimits limits, OutboundBudget budget) {
        return new LoginChannelInitializer(admission.limits().maximumFrameBytes() + 2, decoder, loginHandler, prepender, admission, limits, budget);
    }

    /**
     * Provides the http server channel initializer dependency for the application component.
     *
     * @param handler    the handler or request router
     * @param admission  the shared socket admission owner
     * @param httpLimits the HTTP body and lifetime policy
     * @param limits     the per-channel write allowance
     * @param budget     the aggregate write reservation owner
     * @return the http server channel initializer instance
     */
    @Provides
    @Singleton
    static HttpServerChannelInitializer provideHttpServerChannelInitializer(HttpRequestHandler handler, ConnectionAdmission admission,
                                                                            HttpLimits httpLimits, OutboundLimits limits, OutboundBudget budget) {
        return new HttpServerChannelInitializer(handler, admission, httpLimits, limits, budget);
    }

    /**
     * Provides the web socket server channel initializer dependency for the application component.
     *
     * @param handler   the handler or request router
     * @param admission the shared TCP/WebSocket admission policy
     * @param limits    the per-channel write allowance
     * @param budget    the shared write reservation owner
     * @return the web socket server channel initializer instance
     */
    @Provides
    @Singleton
    static WebSocketServerChannelInitializer provideWebSocketServerChannelInitializer(WebSocketLoginHandler handler, ConnectionAdmission admission,
                                                                                      OutboundLimits limits, OutboundBudget budget) {
        return new WebSocketServerChannelInitializer(handler, admission, limits, budget);
    }

    // ============================================================
    // Server Providers
    // ============================================================

    /**
     * Provides the tcp server dependency for the application component.
     *
     * @param configurationManager the loaded configuration manager
     * @param networkBootstrap     the owner of shared network event-loop groups
     * @param initializer          the initializer for accepted channels
     * @return the tcp server instance
     */
    @Provides
    @Singleton
    @Named(AppContext.TCP_SERVER)
    static Server provideTcpServer(ConfigurationManager configurationManager,
                                   NetworkBootstrap networkBootstrap,
                                   LoginChannelInitializer initializer) {
        return createServer(configurationManager, networkBootstrap, "tcp.port", "tcp.bind.host", initializer);
    }

    /**
     * Provides the http server dependency for the application component.
     *
     * @param configurationManager the loaded configuration manager
     * @param networkBootstrap     the owner of shared network event-loop groups
     * @param initializer          the initializer for accepted channels
     * @return the http server instance
     */
    @Provides
    @Singleton
    @Named(AppContext.HTTP_SERVER)
    static Server provideHttpServer(ConfigurationManager configurationManager,
                                    NetworkBootstrap networkBootstrap,
                                    HttpServerChannelInitializer initializer) {
        return createServer(configurationManager, networkBootstrap, "http.port", "http.bind.host", initializer);
    }

    /**
     * Provides the web socket server dependency for the application component.
     *
     * @param configurationManager the loaded configuration manager
     * @param networkBootstrap     the owner of shared network event-loop groups
     * @param initializer          the initializer for accepted channels
     * @return the web socket server instance
     */
    @Provides
    @Singleton
    @Named(AppContext.WEB_SOCKET_SERVER)
    static Server provideWebSocketServer(ConfigurationManager configurationManager,
                                         NetworkBootstrap networkBootstrap,
                                         WebSocketServerChannelInitializer initializer) {
        return createServer(configurationManager, networkBootstrap, "web.socket.port", "web.socket.bind.host", initializer);
    }

    // ============================================================
    // Helper Methods
    // ============================================================

    /**
     * Creates a server with required listener settings and shared network groups.
     *
     * @param configurationManager the loaded configuration manager
     * @param networkBootstrap     the owner of shared network event-loop groups
     * @param portKey              the configuration key for the bind port
     * @param hostKey              the exact configuration key for the bind address
     * @param initializer          the initializer for accepted channels
     * @return the configured server
     * @throws IllegalArgumentException if the port or host is missing or invalid
     */
    private static Server createServer(ConfigurationManager configurationManager,
                                       NetworkBootstrap networkBootstrap,
                                       String portKey,
                                       String hostKey,
                                       ChannelInitializer<? extends Channel> initializer) {
        NettyConfig config = new NettyConfig();
        int port = configurationManager.getRequiredInt(portKey);
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("Listener port must be between 0 and 65535: " + portKey);
        }
        String host = configurationManager.getRequiredString(hostKey);
        InetSocketAddress address = new InetSocketAddress(host, port);
        if (address.isUnresolved()) {
            throw new IllegalArgumentException("Unresolvable bind host: " + hostKey);
        }
        config.setPortNumber(port);
        config.setSocketAddress(address);
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
