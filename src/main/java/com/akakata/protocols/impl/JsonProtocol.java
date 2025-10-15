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

/**
 * @author Kelvin
 */
public class JsonProtocol extends AbstractNettyProtocol {

    private static final Logger LOG = LoggerFactory.getLogger(JsonProtocol.class);

    private JsonDecoder jsonDecoder;
    private JsonEncoder jsonEncoder;
    private LengthFieldPrepender lengthFieldPrepender;

    public JsonProtocol() {
        super("JSON_PROTOCOL");
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

    public JsonDecoder getJsonDecoder() {
        return jsonDecoder;
    }

    public void setJsonDecoder(JsonDecoder jsonDecoder) {
        this.jsonDecoder = jsonDecoder;
    }

    public JsonEncoder getJsonEncoder() {
        return jsonEncoder;
    }

    public void setJsonEncoder(JsonEncoder jsonEncoder) {
        this.jsonEncoder = jsonEncoder;
    }

    public LengthFieldPrepender getLengthFieldPrepender() {
        return lengthFieldPrepender;
    }

    public void setLengthFieldPrepender(LengthFieldPrepender lengthFieldPrepender) {
        this.lengthFieldPrepender = lengthFieldPrepender;
    }
}
