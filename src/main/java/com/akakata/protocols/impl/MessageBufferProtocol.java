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
 * Installs length framing and binary message-buffer event handlers.
 *
 * <p>Callers must configure the decoder, encoder and length prepender before
 * applying the protocol.
 *
 * @author Kelvin
 * @see MessageBufferEventDecoder
 * @see MessageBufferEventEncoder
 * @see com.akakata.communication.MessageBuffer
 */
public class MessageBufferProtocol extends AbstractNettyProtocol {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(MessageBufferProtocol.class);

    /**
     * Decoder for opcode and binary message-buffer payloads.
     */
    private MessageBufferEventDecoder messageBufferEventDecoder;

    /**
     * Encoder for opcode and binary message-buffer payloads.
     */
    private MessageBufferEventEncoder messageBufferEventEncoder;

    /**
     * Netty handler that prepends the 2-byte length header to outgoing messages.
     * The length field contains the size of the message body (excluding the length field itself).
     */
    private LengthFieldPrepender lengthFieldPrepender;

    /**
     * Creates an unconfigured message-buffer protocol; set its codecs before applying it.
     */
    public MessageBufferProtocol() {
        super("MESSAGE_BUFFER_PROTOCOL");
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
        pipeline.addLast("messageBufferEventDecoder", messageBufferEventDecoder);
        pipeline.addLast("eventHandler", new DefaultToServerHandler(playerSession));

        // Downstream handlers - Filter for data which flows from server to client.
        // Note that the last handler added is actually the first handler for outgoing data.
        pipeline.addLast("lengthFieldPrepender", lengthFieldPrepender);
        pipeline.addLast("messageBufferEventEncoder", messageBufferEventEncoder);
    }

    /**
     * Returns the message buffer event decoder.
     *
     * @return the message buffer event decoder
     */
    public MessageBufferEventDecoder getMessageBufferEventDecoder() {
        return messageBufferEventDecoder;
    }

    /**
     * Sets the message buffer event decoder.
     *
     * @param messageBufferEventDecoder the message buffer event decoder
     */
    public void setMessageBufferEventDecoder(MessageBufferEventDecoder messageBufferEventDecoder) {
        this.messageBufferEventDecoder = messageBufferEventDecoder;
    }

    /**
     * Returns the message buffer event encoder.
     *
     * @return the message buffer event encoder
     */
    public MessageBufferEventEncoder getMessageBufferEventEncoder() {
        return messageBufferEventEncoder;
    }

    /**
     * Sets the message buffer event encoder.
     *
     * @param messageBufferEventEncoder the message buffer event encoder
     */
    public void setMessageBufferEventEncoder(MessageBufferEventEncoder messageBufferEventEncoder) {
        this.messageBufferEventEncoder = messageBufferEventEncoder;
    }

    /**
     * Returns the outbound length-field prepender.
     *
     * @return the outbound length-field prepender
     */
    public LengthFieldPrepender getLengthFieldPrepender() {
        return lengthFieldPrepender;
    }

    /**
     * Sets the outbound length-field prepender.
     *
     * @param lengthFieldPrepender the length field prepender
     */
    public void setLengthFieldPrepender(LengthFieldPrepender lengthFieldPrepender) {
        this.lengthFieldPrepender = lengthFieldPrepender;
    }
}
