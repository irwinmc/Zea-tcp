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

/**
 * TCP 默认协议：SBE 二进制封包。
 */
public class SbeProtocol extends AbstractNettyProtocol {

    private static final Logger LOG = LoggerFactory.getLogger(SbeProtocol.class);

    private SbeEventDecoder sbeEventDecoder;
    private SbeEventEncoder sbeEventEncoder;
    private LengthFieldPrepender lengthFieldPrepender;

    public SbeProtocol() {
        super("SBE_PROTOCOL");
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

    public SbeEventDecoder getSbeEventDecoder() {
        return sbeEventDecoder;
    }

    public void setSbeEventDecoder(SbeEventDecoder sbeEventDecoder) {
        this.sbeEventDecoder = sbeEventDecoder;
    }

    public SbeEventEncoder getSbeEventEncoder() {
        return sbeEventEncoder;
    }

    public void setSbeEventEncoder(SbeEventEncoder sbeEventEncoder) {
        this.sbeEventEncoder = sbeEventEncoder;
    }

    public LengthFieldPrepender getLengthFieldPrepender() {
        return lengthFieldPrepender;
    }

    public void setLengthFieldPrepender(LengthFieldPrepender lengthFieldPrepender) {
        this.lengthFieldPrepender = lengthFieldPrepender;
    }
}
