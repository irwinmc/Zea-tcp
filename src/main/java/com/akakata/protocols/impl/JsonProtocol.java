package com.akakata.protocols.impl;

import com.akakata.app.PlayerSession;
import com.akakata.handlers.DefaultToServerHandler;
import com.akakata.handlers.codec.JsonDecoder;
import com.akakata.handlers.codec.JsonEncoder;
import com.akakata.protocols.AbstractNettyProtocol;
import com.akakata.util.NettyUtils;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldPrepender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

/**
 * Installs two-byte length framing, JSON event codecs and a session inbound handler.
 *
 * @author Kelvin
 * @see JsonDecoder
 * @see JsonEncoder
 */
public class JsonProtocol extends AbstractNettyProtocol {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(JsonProtocol.class);

    /**
     * Shared decoder for opcode and JSON payload frames.
     */
    private final JsonDecoder jsonDecoder;
    /**
     * Shared encoder for opcode and JSON payload frames.
     */
    private final JsonEncoder jsonEncoder;
    /**
     * Shared outbound TCP frame-length encoder.
     */
    private final LengthFieldPrepender lengthFieldPrepender;

    /**
     * Constructs a new JSON protocol with required codec components.
     * <p>
     * All dependencies are injected via Dagger 2 and configured in {@code ProtocolModule}.
     * </p>
     *
     * @param jsonDecoder decodes incoming JSON messages to Event objects
     * @param jsonEncoder encodes outgoing Event objects to JSON format
     * @param lengthFieldPrepender prepends 2-byte length header to outgoing messages
     */
    @Inject
    public JsonProtocol(JsonDecoder jsonDecoder,
                       JsonEncoder jsonEncoder,
                       LengthFieldPrepender lengthFieldPrepender) {
        super("JSON_PROTOCOL");
        this.jsonDecoder = jsonDecoder;
        this.jsonEncoder = jsonEncoder;
        this.lengthFieldPrepender = lengthFieldPrepender;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void applyProtocol(PlayerSession playerSession) {
        LOG.debug("Going to apply protocol on session: {}.", playerSession);

        ChannelPipeline pipeline = NettyUtils.getPipeLineOfConnection(playerSession);

        // Upstream handlers or encoders (i.e towards server) are added to pipeline now.
        pipeline.addLast("lengthDecoder", createLengthBasedFrameDecoder());
        pipeline.addLast("jsonDecoder", jsonDecoder);
        pipeline.addLast("eventHandler", new DefaultToServerHandler(playerSession));

        // Downstream handlers (i.e towards client) are added to pipeline now.
        // NOTE the last encoder in the pipeline is the first encoder to be called.
        pipeline.addLast("lengthFieldPrepender", lengthFieldPrepender);
        pipeline.addLast("jsonEncoder", jsonEncoder);
    }
}
