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
 * Installs two-byte length framing and the project's SBE-style event codecs.
 *
 * @author Kelvin
 * @see SbeEventDecoder
 * @see SbeEventEncoder
 */
public class SbeProtocol extends AbstractNettyProtocol {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(SbeProtocol.class);

    /**
     * Decoder for the project's SBE-style event frames.
     */
    private final SbeEventDecoder sbeEventDecoder;

    /**
     * Encoder for the project's SBE-style event frames.
     */
    private final SbeEventEncoder sbeEventEncoder;

    /**
     * Shared outbound TCP frame-length encoder.
     */
    private final LengthFieldPrepender lengthFieldPrepender;

    /**
     * Constructs a new SBE protocol with required codec components.
     * <p>
     * All dependencies are injected via Dagger 2 and configured in {@code ProtocolModule}.
     * </p>
     *
     * @param sbeEventDecoder decodes incoming SBE binary messages to Event objects
     * @param sbeEventEncoder encodes outgoing Event objects to SBE binary format
     * @param lengthFieldPrepender prepends 2-byte length header to outgoing messages
     */
    @Inject
    public SbeProtocol(SbeEventDecoder sbeEventDecoder,
                      SbeEventEncoder sbeEventEncoder,
                      LengthFieldPrepender lengthFieldPrepender) {
        super("SBE_PROTOCOL");
        this.sbeEventDecoder = sbeEventDecoder;
        this.sbeEventEncoder = sbeEventEncoder;
        this.lengthFieldPrepender = lengthFieldPrepender;
    }

    /**
     * {@inheritDoc}
     */
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
