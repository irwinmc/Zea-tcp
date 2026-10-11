package com.akakata.server.initializer;

import com.akakata.context.AppContext;
import com.akakata.handlers.WebSocketLoginHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;

import javax.inject.Inject;

/**
 * Installs HTTP upgrade handling, WebSocket compression and the binary login handler.
 *
 * @author Kyia
 */
public class WebSocketServerChannelInitializer extends ChannelInitializer<SocketChannel> {

    /**
     * HTTP path accepted for WebSocket upgrades.
     */
    private static final String WEBSOCKET_PATH = "/";

    /**
     * Handler that authenticates binary WebSocket login frames.
     */
    private final WebSocketLoginHandler webSocketLoginHandler;

    /**
     * Creates a web socket server channel initializer instance with the supplied dependencies.
     *
     * @param webSocketLoginHandler the web socket login handler
     */
    @Inject
    public WebSocketServerChannelInitializer(WebSocketLoginHandler webSocketLoginHandler) {
        this.webSocketLoginHandler = webSocketLoginHandler;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();

        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(65536));
        // A zero allocation limit keeps the existing decompression behavior.
        pipeline.addLast(new WebSocketServerCompressionHandler(0));
        pipeline.addLast(new WebSocketServerProtocolHandler(WEBSOCKET_PATH, null, true));
        pipeline.addLast(AppContext.WEB_SOCKET_LOGIN_HANDLER, webSocketLoginHandler);
    }
}
