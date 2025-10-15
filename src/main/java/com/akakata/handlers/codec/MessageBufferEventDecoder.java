package com.akakata.handlers.codec;

import com.akakata.communication.impl.NettyMessageBuffer;
import com.akakata.event.Event;
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
public class MessageBufferEventDecoder extends MessageToMessageDecoder<ByteBuf> {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        out.add(decode(ctx, msg));
    }

    public Event decode(ChannelHandlerContext ctx, ByteBuf msg) {
        int opcode = msg.readUnsignedByte();
        if (opcode == Events.NETWORK_MESSAGE) {
            opcode = Events.SESSION_MESSAGE;
        }
        ByteBuf data = msg.readBytes(msg.readableBytes());
        return Events.event(new NettyMessageBuffer(data), opcode);
    }
}
