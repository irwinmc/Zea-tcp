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
 * @author Kelvin
 */
public class JsonProtocol extends AbstractNettyProtocol {

    private static final Logger LOG = LoggerFactory.getLogger(JsonProtocol.class);

    private final JsonDecoder jsonDecoder;
    private final JsonEncoder jsonEncoder;
    private final LengthFieldPrepender lengthFieldPrepender;

    @Inject
    public JsonProtocol(JsonDecoder jsonDecoder,
                       JsonEncoder jsonEncoder,
                       LengthFieldPrepender lengthFieldPrepender) {
        super("JSON_PROTOCOL");
        this.jsonDecoder = jsonDecoder;
        this.jsonEncoder = jsonEncoder;
        this.lengthFieldPrepender = lengthFieldPrepender;
    }

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
