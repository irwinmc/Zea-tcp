package com.akakata.handlers.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Appends a zero-byte delimiter to outbound byte-buffer messages.
 *
 * @author Kelvin
 */
@Sharable
public class NulEncoder extends MessageToByteEncoder<ByteBuf> {

    /**
     * Creates a nul encoder instance.
     */
    public NulEncoder() {
    }

    /**
     * Shared one-byte zero delimiter appended by the encoder.
     */
    private static final ByteBuf NULL_BUFFER = Unpooled.wrappedBuffer(new byte[]{0});

    /**
     * {@inheritDoc}
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        out.writeBytes(Unpooled.wrappedBuffer(msg, NULL_BUFFER));
    }
}
