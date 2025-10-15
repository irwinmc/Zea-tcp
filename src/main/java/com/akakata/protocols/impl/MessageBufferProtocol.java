package com.akakata.protocols.impl;

import com.akakata.app.PlayerSession;
import com.akakata.handlers.DefaultToServerHandler;
import com.akakata.handlers.codec.MessageBufferEventDecoder;
import com.akakata.handlers.codec.MessageBufferEventEncoder;
import com.akakata.protocols.AbstractNettyProtocol;
import com.akakata.util.NettyUtils;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldPrepender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Kelvin
 */
public class MessageBufferProtocol extends AbstractNettyProtocol {

    private static final Logger LOG = LoggerFactory.getLogger(MessageBufferProtocol.class);

    /**
     * Utility handler provided by netty to add the length of the outgoing
     * message to the message as a header.
     */
    private MessageBufferEventDecoder messageBufferEventDecoder;
    private MessageBufferEventEncoder messageBufferEventEncoder;
    private LengthFieldPrepender lengthFieldPrepender;

    public MessageBufferProtocol() {
        super("MESSAGE_BUFFER_PROTOCOL");
    }

    @Override
    public void applyProtocol(PlayerSession playerSession) {
        LOG.debug("Going to apply protocol on session: {}.", playerSession);

        ChannelPipeline pipeline = NettyUtils.getPipeLineOfConnection(playerSession);

        // Upstream handlers or encoders (i.e towards server) are added to pipeline now.
        pipeline.addLast("lengthDecoder", createLengthBasedFrameDecoder());
        pipeline.addLast("messageBufferEventDecoder", messageBufferEventDecoder);
        pipeline.addLast("eventHandler", new DefaultToServerHandler(playerSession));

        // Downstream handlers - Filter for data which flows from server to client.
        // Note that the last handler added is actually the first handler for outgoing data.
        pipeline.addLast("lengthFieldPrepender", lengthFieldPrepender);
        pipeline.addLast("messageBufferEventEncoder", messageBufferEventEncoder);
    }

    public MessageBufferEventDecoder getMessageBufferEventDecoder() {
        return messageBufferEventDecoder;
    }

    public void setMessageBufferEventDecoder(MessageBufferEventDecoder messageBufferEventDecoder) {
        this.messageBufferEventDecoder = messageBufferEventDecoder;
    }

    public MessageBufferEventEncoder getMessageBufferEventEncoder() {
        return messageBufferEventEncoder;
    }

    public void setMessageBufferEventEncoder(MessageBufferEventEncoder messageBufferEventEncoder) {
        this.messageBufferEventEncoder = messageBufferEventEncoder;
    }

    public LengthFieldPrepender getLengthFieldPrepender() {
        return lengthFieldPrepender;
    }

    public void setLengthFieldPrepender(LengthFieldPrepender lengthFieldPrepender) {
        this.lengthFieldPrepender = lengthFieldPrepender;
    }
}
