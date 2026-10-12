package com.akakata.server.initializer;

import com.akakata.context.AppContext;
import com.akakata.communication.OutboundBudget;
import com.akakata.communication.OutboundLimits;
import com.akakata.communication.impl.SocketMessageSender;
import com.akakata.handlers.TransportLifecycleHandler;
import com.akakata.handlers.WebSocketLoginHandler;
import com.akakata.server.ConnectionAdmission;
import com.akakata.server.TransportLimits;
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
     * Shared socket policy covering upgrade and authentication.
     */
    private final ConnectionAdmission admission;

    /**
     * Per-channel encoded write allowance.
     */
    private final OutboundLimits outboundLimits;

    /**
     * Shared aggregate write owner, including upgrade responses.
     */
    private final OutboundBudget budget;

    /**
     * Creates a web socket server channel initializer instance with the supplied dependencies.
     *
     * @param webSocketLoginHandler the web socket login handler
     */
    @Inject
    public WebSocketServerChannelInitializer(WebSocketLoginHandler webSocketLoginHandler) {
        this(webSocketLoginHandler, new ConnectionAdmission(TransportLimits.DEFAULT));
    }

    /**
     * Creates WebSocket upgrade handling with a shared socket lifetime policy.
     *
     * @param webSocketLoginHandler the authentication handler
     * @param admission             the TCP/WebSocket admission owner
     */
    public WebSocketServerChannelInitializer(WebSocketLoginHandler webSocketLoginHandler,
                                             ConnectionAdmission admission) {
        this(webSocketLoginHandler, admission, OutboundLimits.DEFAULT, OutboundBudget.DEFAULT);
    }

    /**
     * Creates upgrade and login handling using the context's shared resource budgets.
     *
     * @param webSocketLoginHandler the authentication handler
     * @param admission the shared connection owner
     * @param outboundLimits the per-channel write allowance
     * @param budget the aggregate reservation owner
     */
    public WebSocketServerChannelInitializer(WebSocketLoginHandler webSocketLoginHandler,
                                             ConnectionAdmission admission, OutboundLimits outboundLimits, OutboundBudget budget) {
        this.admission = admission;
        this.webSocketLoginHandler = webSocketLoginHandler;
        this.outboundLimits = outboundLimits;
        this.budget = budget;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        ChannelPipeline pipeline = ch.pipeline();
        SocketMessageSender.installOutboundBudget(pipeline, outboundLimits, budget);
        TransportLifecycleHandler.install(pipeline, admission);
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(65536));
        int maximum = admission.limits().maximumFrameBytes();
        pipeline.addLast(new WebSocketServerCompressionHandler(maximum));
        pipeline.addLast(new WebSocketServerProtocolHandler(
                io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig.newBuilder()
                        .websocketPath(WEBSOCKET_PATH).allowExtensions(true).maxFramePayloadLength(maximum).build()));
        pipeline.addLast(AppContext.WEB_SOCKET_LOGIN_HANDLER, webSocketLoginHandler);
    }
}
