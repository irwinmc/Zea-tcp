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
 * @author Kyia
 */
public class StringProtocol extends AbstractNettyProtocol {

    private int frameSize;
    private NulEncoder nulEncoder;
    private StringDecoder stringDecoder;
    private StringEncoder stringEncoder;

    public StringProtocol() {
        super("STRING_PROTOCOL");
    }

    public StringProtocol(int frameSize, NulEncoder nulEncoder,
                          StringDecoder stringDecoder, StringEncoder stringEncoder) {
        super("STRING_PROTOCOL");
        this.frameSize = frameSize;
        this.nulEncoder = nulEncoder;
        this.stringDecoder = stringDecoder;
        this.stringEncoder = stringEncoder;
    }

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

    public int getFrameSize() {
        return frameSize;
    }

    public void setFrameSize(int frameSize) {
        this.frameSize = frameSize;
    }

    public NulEncoder getNulEncoder() {
        return nulEncoder;
    }

    public void setNulEncoder(NulEncoder nulEncoder) {
        this.nulEncoder = nulEncoder;
    }

    public StringDecoder getStringDecoder() {
        return stringDecoder;
    }

    public void setStringDecoder(StringDecoder stringDecoder) {
        this.stringDecoder = stringDecoder;
    }

    public StringEncoder getStringEncoder() {
        return stringEncoder;
    }

    public void setStringEncoder(StringEncoder stringEncoder) {
        this.stringEncoder = stringEncoder;
    }
}
