package com.akakata.handlers.codec;

import com.akakata.event.Events;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

/**
 * @author Kelvin
 */
@Sharable
public class EventDecoder extends MessageToMessageDecoder<ByteBuf> {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        int opcode = msg.readUnsignedByte();
        ByteBuf buffer = msg.readBytes(msg.readableBytes());
        out.add(Events.event(buffer, opcode));
    }
}
