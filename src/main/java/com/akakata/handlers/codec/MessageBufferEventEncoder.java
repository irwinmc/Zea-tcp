package com.akakata.handlers.codec;

import com.akakata.communication.MessageBuffer;
import com.akakata.event.Event;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * @author Kelvin
 */
@Sharable
public class MessageBufferEventEncoder extends MessageToMessageEncoder<Event> {

    private static final Logger LOG = LoggerFactory.getLogger(MessageBufferEventEncoder.class);

    @Override
    protected void encode(ChannelHandlerContext ctx, Event event, List<Object> out) throws Exception {
        out.add(encode(ctx, event));
    }

    @SuppressWarnings("unchecked")
    protected ByteBuf encode(ChannelHandlerContext ctx, Event event) {
        ByteBuf msg = null;
        if (event.getSource() != null) {
            MessageBuffer<ByteBuf> msgBuffer = (MessageBuffer<ByteBuf>) event.getSource();
            ByteBuf data = msgBuffer.getNativeBuffer();
            ByteBuf opcode = ctx.alloc().buffer(1);
            opcode.writeByte(event.getType());
            msg = Unpooled.wrappedBuffer(opcode, data);
        } else {
            msg = ctx.alloc().buffer(1);
            msg.writeByte(event.getType());
        }
        return msg;
    }
}
