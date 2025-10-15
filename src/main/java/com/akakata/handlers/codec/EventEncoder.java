package com.akakata.handlers.codec;

import com.akakata.event.Event;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.util.List;

/**
 * @author Kelvin
 */
@Sharable
public class EventEncoder extends MessageToMessageEncoder<Event> {

    @Override
    protected void encode(ChannelHandlerContext ctx, Event event, List<Object> out) throws Exception {
        ByteBuf opcode = ctx.alloc().buffer(1);
        opcode.writeByte(event.getType());
        if (event.getSource() != null) {
            ByteBuf data = (ByteBuf) event.getSource();
            ByteBuf compositeBuffer = Unpooled.wrappedBuffer(opcode, data);
            out.add(compositeBuffer);
        } else {
            out.add(opcode);
        }
    }
}
