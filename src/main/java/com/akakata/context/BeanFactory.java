package com.akakata.context;

import com.akakata.app.Game;
import com.akakata.app.impl.DefaultGame;
import com.akakata.handlers.*;
import com.akakata.handlers.codec.*;
import com.akakata.protocols.impl.JsonProtocol;
import com.akakata.protocols.impl.WebSocketProtocol;
import com.akakata.server.NettyConfig;
import com.akakata.server.impl.NettyTCPServer;
import com.akakata.server.initializer.HttpServerChannelInitializer;
import com.akakata.server.initializer.LoginChannelInitializer;
import com.akakata.server.initializer.WebSocketServerChannelInitializer;
import com.akakata.server.http.ApiHandler;
import com.akakata.server.http.IndexPageHandler;
import com.akakata.server.http.RpcHandler;
import com.akakata.service.impl.SimpleSessionManagerServiceImpl;
import com.akakata.service.impl.SimpleTaskManagerServiceImpl;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.handler.codec.LengthFieldPrepender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Factory for creating and wiring application beans.
 * Single responsibility: Bean creation and dependency injection.
 *
 * @author Kelvin
 */
public final class BeanFactory {

    private static final Logger LOG = LoggerFactory.getLogger(BeanFactory.class);

    private final ConfigurationManager config;
    private final NetworkBootstrap network;
    private final BeanRegistry registry;

    public BeanFactory(ConfigurationManager config, NetworkBootstrap network, BeanRegistry registry) {
        this.config = config;
        this.network = network;
        this.registry = registry;
    }

    /**
     * Create and register all application beans.
     */
    public void createBeans() {
        LOG.info("Creating application beans...");

        // 1. Core services
        createServices();

        // 2. Codecs (only what we need)
        LengthFieldPrepender lengthFieldPrepender = new LengthFieldPrepender(2, false);
        registry.register(AppContext.LENGTH_FIELD_PREPENDER, lengthFieldPrepender);

        EventDecoder eventDecoder = new EventDecoder();
        JsonDecoder jsonDecoder = new JsonDecoder();
        JsonEncoder jsonEncoder = new JsonEncoder();
        WebSocketEventDecoder webSocketEventDecoder = new WebSocketEventDecoder();
        WebSocketEventEncoder webSocketEventEncoder = new WebSocketEventEncoder();

        // 3. Protocols
        JsonProtocol jsonProtocol = createJsonProtocol(jsonDecoder, jsonEncoder, lengthFieldPrepender);
        WebSocketProtocol webSocketProtocol = createWebSocketProtocol(webSocketEventDecoder, webSocketEventEncoder);

        // 4. Handlers
        LoginHandler loginHandler = createLoginHandler(jsonProtocol);
        WebSocketLoginHandler webSocketLoginHandler = createWebSocketLoginHandler(webSocketProtocol);
        HttpRequestHandler httpRequestHandler = createHttpRequestHandler();

        // 5. Channel Initializers
        LoginChannelInitializer loginChannelInit = createLoginChannelInitializer(eventDecoder, loginHandler, lengthFieldPrepender);
        HttpServerChannelInitializer httpChannelInit = createHttpChannelInitializer(httpRequestHandler);
        WebSocketServerChannelInitializer wsChannelInit = createWebSocketChannelInitializer(webSocketLoginHandler);

        // 6. Servers
        registry.register(AppContext.TCP_SERVER, createServer("tcp.port", 8090, loginChannelInit));
        registry.register(AppContext.HTTP_SERVER, createServer("http.port", 8081, httpChannelInit));
        registry.register(AppContext.WEB_SOCKET_SERVER, createServer("web.socket.port", 8300, wsChannelInit));

        LOG.info("Created {} beans", registry.size());
    }

    private void createServices() {
        registry.register(AppContext.APP_SESSION, new DefaultGame());
        registry.register(AppContext.SESSION_REGISTRY_SERVICE, new SimpleSessionManagerServiceImpl());
        registry.register(AppContext.TASK_MANAGER_SERVICE, new SimpleTaskManagerServiceImpl(2));
    }

    private JsonProtocol createJsonProtocol(JsonDecoder decoder, JsonEncoder encoder, LengthFieldPrepender prepender) {
        JsonProtocol protocol = new JsonProtocol();
        protocol.setJsonDecoder(decoder);
        protocol.setJsonEncoder(encoder);
        protocol.setLengthFieldPrepender(prepender);
        return protocol;
    }

    private WebSocketProtocol createWebSocketProtocol(WebSocketEventDecoder decoder, WebSocketEventEncoder encoder) {
        WebSocketProtocol protocol = new WebSocketProtocol();
        protocol.setWebSocketEventDecoder(decoder);
        protocol.setWebSocketEventEncoder(encoder);
        return protocol;
    }

    private LoginHandler createLoginHandler(JsonProtocol protocol) {
        LoginHandler handler = new LoginHandler();
        handler.setProtocol(protocol);
        handler.setGame(registry.getBean(AppContext.APP_SESSION, Game.class));
        handler.setSessionManagerService(registry.getBean(AppContext.SESSION_REGISTRY_SERVICE,
                SimpleSessionManagerServiceImpl.class));
        return handler;
    }

    private WebSocketLoginHandler createWebSocketLoginHandler(WebSocketProtocol protocol) {
        WebSocketLoginHandler handler = new WebSocketLoginHandler();
        handler.setProtocol(protocol);
        handler.setGame(registry.getBean(AppContext.APP_SESSION, Game.class));
        handler.setSessionManagerService(registry.getBean(AppContext.SESSION_REGISTRY_SERVICE,
                SimpleSessionManagerServiceImpl.class));
        registry.register(AppContext.WEB_SOCKET_LOGIN_HANDLER, handler);
        return handler;
    }

    private HttpRequestHandler createHttpRequestHandler() {
        HttpRequestHandler handler = new HttpRequestHandler();
        handler.setIndexPageHandler(new IndexPageHandler());
        handler.setApiHandler(new ApiHandler());
        handler.setRpcHandler(new RpcHandler());
        return handler;
    }

    private LoginChannelInitializer createLoginChannelInitializer(EventDecoder decoder, LoginHandler handler,
                                                                   LengthFieldPrepender prepender) {
        LoginChannelInitializer initializer = new LoginChannelInitializer();
        initializer.setEventDecoder(decoder);
        initializer.setLoginHandler(handler);
        initializer.setLengthFieldPrepender(prepender);
        return initializer;
    }

    private HttpServerChannelInitializer createHttpChannelInitializer(HttpRequestHandler handler) {
        HttpServerChannelInitializer initializer = new HttpServerChannelInitializer();
        initializer.setHttpRequestHandler(handler);
        return initializer;
    }

    private WebSocketServerChannelInitializer createWebSocketChannelInitializer(WebSocketLoginHandler handler) {
        WebSocketServerChannelInitializer initializer = new WebSocketServerChannelInitializer();
        initializer.setWebSocketLoginHandler(handler);
        return initializer;
    }

    private NettyTCPServer createServer(String portKey, int defaultPort,
                                        ChannelInitializer<? extends Channel> initializer) {
        NettyConfig config = new NettyConfig();
        config.setPortNumber(this.config.getInt(portKey, defaultPort));
        config.setBossGroup(network.getBossGroup());
        config.setWorkerGroup(network.getWorkerGroup());

        // Channel options
        Map<ChannelOption<?>, Object> channelOptions = new HashMap<>();
        channelOptions.put(ChannelOption.SO_BACKLOG, this.config.getInt("so.backlog", 100));
        channelOptions.put(ChannelOption.SO_REUSEADDR, this.config.getBoolean("so.reuseaddr", true));
        config.setChannelOptions(channelOptions);

        // Child channel options
        Map<ChannelOption<?>, Object> childOptions = new HashMap<>();
        childOptions.put(ChannelOption.SO_KEEPALIVE, this.config.getBoolean("so.keepalive", true));
        childOptions.put(ChannelOption.TCP_NODELAY, this.config.getBoolean("tcp.nodelay", true));
        config.setChannelChildOptions(childOptions);

        return new NettyTCPServer(config, initializer);
    }
}
