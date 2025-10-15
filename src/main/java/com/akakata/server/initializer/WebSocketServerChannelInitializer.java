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

/**
 * @author Kyia
 */
public class WebSocketServerChannelInitializer extends ChannelInitializer<SocketChannel> {

    private static final String WEBSOCKET_PATH = "/";

    private WebSocketLoginHandler webSocketLoginHandler;

    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();

        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(65536));
        pipeline.addLast(new WebSocketServerCompressionHandler());
        pipeline.addLast(new WebSocketServerProtocolHandler(WEBSOCKET_PATH, null, true));
        pipeline.addLast(AppContext.WEB_SOCKET_LOGIN_HANDLER, webSocketLoginHandler);
    }

    public WebSocketLoginHandler getWebSocketLoginHandler() {
        return webSocketLoginHandler;
    }

    public void setWebSocketLoginHandler(WebSocketLoginHandler webSocketLoginHandler) {
        this.webSocketLoginHandler = webSocketLoginHandler;
    }
}
