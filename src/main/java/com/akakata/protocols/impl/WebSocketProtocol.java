package com.akakata.protocols.impl;

import com.akakata.app.PlayerSession;
import com.akakata.context.AppContext;
import com.akakata.handlers.DefaultToServerHandler;
import com.akakata.handlers.codec.WebSocketEventDecoder;
import com.akakata.handlers.codec.WebSocketEventEncoder;
import com.akakata.protocols.AbstractNettyProtocol;
import com.akakata.util.NettyUtils;
import io.netty.channel.ChannelPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Kelvin
 */
public class WebSocketProtocol extends AbstractNettyProtocol {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketProtocol.class);

    private WebSocketEventDecoder webSocketEventDecoder;
    private WebSocketEventEncoder webSocketEventEncoder;

    public WebSocketProtocol() {
        super("WEB_SOCKET_PROTOCOL");
    }

    @Override
    public void applyProtocol(PlayerSession playerSession) {
        LOG.trace("Going to apply {} on session: {}", getProtocolName(), playerSession);

        ChannelPipeline pipeline = NettyUtils.getPipeLineOfConnection(playerSession);

        // Remove login handler
        pipeline.remove(AppContext.WEB_SOCKET_LOGIN_HANDLER);

        // Add more handler
        pipeline.addLast("webSocketEventDecoder", webSocketEventDecoder);
        pipeline.addLast("eventHandler", new DefaultToServerHandler(playerSession));
        pipeline.addLast("webSocketEventEncoder", webSocketEventEncoder);
    }

    public WebSocketEventDecoder getWebSocketEventDecoder() {
        return webSocketEventDecoder;
    }

    public void setWebSocketEventDecoder(WebSocketEventDecoder webSocketEventDecoder) {
        this.webSocketEventDecoder = webSocketEventDecoder;
    }

    public WebSocketEventEncoder getWebSocketEventEncoder() {
        return webSocketEventEncoder;
    }

    public void setWebSocketEventEncoder(WebSocketEventEncoder webSocketEventEncoder) {
        this.webSocketEventEncoder = webSocketEventEncoder;
    }
}
