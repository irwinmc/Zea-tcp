package com.akakata.protocols.impl;

import com.akakata.app.PlayerSession;
import com.akakata.handlers.DefaultToServerHandler;
import com.akakata.handlers.codec.SbeEventDecoder;
import com.akakata.handlers.codec.SbeEventEncoder;
import com.akakata.protocols.AbstractNettyProtocol;
import com.akakata.util.NettyUtils;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldPrepender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

/**
 * TCP 默认协议：SBE 二进制封包。
 */
public class SbeProtocol extends AbstractNettyProtocol {

    private static final Logger LOG = LoggerFactory.getLogger(SbeProtocol.class);

    private final SbeEventDecoder sbeEventDecoder;
    private final SbeEventEncoder sbeEventEncoder;
    private final LengthFieldPrepender lengthFieldPrepender;

    @Inject
    public SbeProtocol(SbeEventDecoder sbeEventDecoder,
                      SbeEventEncoder sbeEventEncoder,
                      LengthFieldPrepender lengthFieldPrepender) {
        super("SBE_PROTOCOL");
        this.sbeEventDecoder = sbeEventDecoder;
        this.sbeEventEncoder = sbeEventEncoder;
        this.lengthFieldPrepender = lengthFieldPrepender;
    }

    @Override
    public void applyProtocol(PlayerSession playerSession) {
        LOG.debug("Applying SBE protocol on session {}", playerSession);

        ChannelPipeline pipeline = NettyUtils.getPipeLineOfConnection(playerSession);
        pipeline.addLast("lengthDecoder", createLengthBasedFrameDecoder());
        pipeline.addLast("sbeDecoder", sbeEventDecoder);
        pipeline.addLast("eventHandler", new DefaultToServerHandler(playerSession));
        pipeline.addLast("lengthFieldPrepender", lengthFieldPrepender);
        pipeline.addLast("sbeEncoder", sbeEventEncoder);
    }
}
