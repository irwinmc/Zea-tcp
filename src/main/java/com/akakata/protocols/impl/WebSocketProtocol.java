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

import javax.inject.Inject;

/**
 * @author Kelvin
 */
public class WebSocketProtocol extends AbstractNettyProtocol {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketProtocol.class);

    private final WebSocketEventDecoder webSocketEventDecoder;
    private final WebSocketEventEncoder webSocketEventEncoder;

    @Inject
    public WebSocketProtocol(WebSocketEventDecoder webSocketEventDecoder,
                            WebSocketEventEncoder webSocketEventEncoder) {
        super("WEB_SOCKET_PROTOCOL");
        this.webSocketEventDecoder = webSocketEventDecoder;
        this.webSocketEventEncoder = webSocketEventEncoder;
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
}
