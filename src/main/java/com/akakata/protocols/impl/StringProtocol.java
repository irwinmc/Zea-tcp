package com.akakata.protocols.impl;

import com.akakata.app.PlayerSession;
import com.akakata.handlers.codec.NulEncoder;
import com.akakata.protocols.AbstractNettyProtocol;
import com.akakata.util.NettyUtils;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

/**
 * Installs zero-delimited string framing and string codecs.
 *
 * <p>The no-argument instance requires frame-size and codec configuration before use.
 *
 * @author Kyia
 * @see StringDecoder
 * @see StringEncoder
 * @see DelimiterBasedFrameDecoder
 */
public class StringProtocol extends AbstractNettyProtocol {

    /**
     * Maximum inbound frame size in bytes.
     */
    private int frameSize;

    /**
     * Custom encoder that appends null terminator to outgoing string messages.
     */
    private NulEncoder nulEncoder;

    /**
     * Netty's built-in decoder that converts ByteBuf to String using UTF-8 encoding.
     */
    private StringDecoder stringDecoder;

    /**
     * Netty's built-in encoder that converts String to ByteBuf using UTF-8 encoding.
     */
    private StringEncoder stringEncoder;

    /**
     * Creates a string protocol. The no-argument instance requires codecs and frame size to be
     * configured before use.
     */
    public StringProtocol() {
        super("STRING_PROTOCOL");
    }

    /**
     * Creates a string protocol. The no-argument instance requires codecs and frame size to be
     * configured before use.
     *
     * @param frameSize maximum frame size in bytes (prevents memory exhaustion attacks)
     * @param nulEncoder encoder that appends null terminator to outgoing messages
     * @param stringDecoder decoder that converts ByteBuf to UTF-8 String
     * @param stringEncoder encoder that converts String to UTF-8 ByteBuf
     */
    public StringProtocol(int frameSize, NulEncoder nulEncoder,
                          StringDecoder stringDecoder, StringEncoder stringEncoder) {
        super("STRING_PROTOCOL");
        this.frameSize = frameSize;
        this.nulEncoder = nulEncoder;
        this.stringDecoder = stringDecoder;
        this.stringEncoder = stringEncoder;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void applyProtocol(PlayerSession playerSession) {
        ChannelPipeline pipeline = NettyUtils.getPipeLineOfConnection(playerSession);

        // Upstream handlers or encoders (i.e towards server) are added to
        // pipeline now.
        pipeline.addLast("framer", new DelimiterBasedFrameDecoder(frameSize, Delimiters.nulDelimiter()));
        pipeline.addLast("stringDecoder", stringDecoder);

        // Downstream handlers (i.e towards client) are added to pipeline now.
        pipeline.addLast("nulEncoder", nulEncoder);
        pipeline.addLast("stringEncoder", stringEncoder);
    }

    /**
     * Returns the maximum frame size in bytes.
     *
     * @return the maximum frame size in bytes
     */
    public int getFrameSize() {
        return frameSize;
    }

    /**
     * Sets the maximum frame size in bytes.
     *
     * @param frameSize the maximum accepted frame size in bytes
     */
    public void setFrameSize(int frameSize) {
        this.frameSize = frameSize;
    }

    /**
     * Returns the nul encoder.
     *
     * @return the nul encoder
     */
    public NulEncoder getNulEncoder() {
        return nulEncoder;
    }

    /**
     * Sets the nul encoder.
     *
     * @param nulEncoder the nul encoder
     */
    public void setNulEncoder(NulEncoder nulEncoder) {
        this.nulEncoder = nulEncoder;
    }

    /**
     * Returns the string decoder.
     *
     * @return the string decoder
     */
    public StringDecoder getStringDecoder() {
        return stringDecoder;
    }

    /**
     * Sets the string decoder.
     *
     * @param stringDecoder the string decoder
     */
    public void setStringDecoder(StringDecoder stringDecoder) {
        this.stringDecoder = stringDecoder;
    }

    /**
     * Returns the string encoder.
     *
     * @return the string encoder
     */
    public StringEncoder getStringEncoder() {
        return stringEncoder;
    }

    /**
     * Sets the string encoder.
     *
     * @param stringEncoder the string encoder
     */
    public void setStringEncoder(StringEncoder stringEncoder) {
        this.stringEncoder = stringEncoder;
    }
}
