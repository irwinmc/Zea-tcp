package com.akakata.protocols;

import com.akakata.app.PlayerSession;
import com.akakata.util.NettyUtils;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * @author Kelvin
 */
public abstract class AbstractNettyProtocol implements Protocol {

    /**
     * The name of the protocol. This is set by the child class to appropriate
     * value while child class instance is created.
     */
    final String protocolName;

    public AbstractNettyProtocol(String protocolName) {
        super();
        this.protocolName = protocolName;
    }

    public LengthFieldBasedFrameDecoder createLengthBasedFrameDecoder() {
        return new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 2, 0, 2);
    }

    @Override
    public String getProtocolName() {
        return protocolName;
    }

    @Override
    public void applyProtocol(PlayerSession playerSession, boolean clearExistingProtocolHandlers) {
        if (clearExistingProtocolHandlers) {
            ChannelPipeline pipeline = NettyUtils.getPipeLineOfConnection(playerSession);
            NettyUtils.clearPipeline(pipeline);
        }
        applyProtocol(playerSession);
    }
}
