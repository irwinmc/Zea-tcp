package com.akakata.protocols.impl;

import com.akakata.app.PlayerSession;
import com.akakata.context.AppContext;
import com.akakata.handlers.ApplicationMetricsHandler;
import com.akakata.handlers.DefaultToServerHandler;
import com.akakata.handlers.codec.WebSocketEventDecoder;
import com.akakata.handlers.codec.WebSocketEventEncoder;
import com.akakata.metrics.ServerMetrics;
import com.akakata.protocols.AbstractNettyProtocol;
import com.akakata.util.NettyUtils;
import io.netty.channel.ChannelPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

/**
 * Replaces the WebSocket login handler with binary JSON event handlers after authentication.
 *
 * @author Kelvin
 * @see WebSocketEventDecoder
 * @see WebSocketEventEncoder
 * @see io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
 */
public class WebSocketProtocol extends AbstractNettyProtocol {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(WebSocketProtocol.class);

    /**
     * Decoder for binary WebSocket event frames.
     */
    private final WebSocketEventDecoder webSocketEventDecoder;

    /**
     * Encoder for binary WebSocket event frames.
     */
    private final WebSocketEventEncoder webSocketEventEncoder;

    /**
     * Constructs a new WebSocket protocol with required codec components.
     * <p>
     * All dependencies are injected via Dagger 2 and configured in {@code ProtocolModule}.
     * </p>
     *
     * @param webSocketEventDecoder decodes WebSocket frames to Event objects
     * @param webSocketEventEncoder encodes Event objects to WebSocket frames
     */
    @Inject
    public WebSocketProtocol(WebSocketEventDecoder webSocketEventDecoder,
                             WebSocketEventEncoder webSocketEventEncoder) {
        super("WEB_SOCKET_PROTOCOL");
        this.webSocketEventDecoder = webSocketEventDecoder;
        this.webSocketEventEncoder = webSocketEventEncoder;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void applyProtocol(PlayerSession playerSession) {
        LOG.trace("Going to apply {} on session: {}", getProtocolName(), playerSession);

        ChannelPipeline pipeline = NettyUtils.getPipeLineOfConnection(playerSession);

        // Remove login handler
        pipeline.remove(AppContext.WEB_SOCKET_LOGIN_HANDLER);

        // Add more handler
        pipeline.addLast("webSocketEventDecoder", webSocketEventDecoder);
        pipeline.addLast("applicationMetrics", new ApplicationMetricsHandler(ServerMetrics.getInstance()));
        pipeline.addLast("eventHandler", new DefaultToServerHandler(playerSession));
        pipeline.addLast("webSocketEventEncoder", webSocketEventEncoder);
    }
}
