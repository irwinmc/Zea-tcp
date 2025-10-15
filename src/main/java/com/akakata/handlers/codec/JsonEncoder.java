package com.akakata.handlers.codec;

import com.akakata.event.Event;
import com.akakata.util.NettyUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
public class JsonEncoder extends MessageToMessageEncoder<Event> {

    private final ObjectMapper objectMapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    @Override
    protected void encode(ChannelHandlerContext ctx, Event event, List<Object> out) throws Exception {
        ByteBuf msg;
        if (event.getSource() != null) {
            ByteBuf buf = ctx.alloc().buffer(1);
            buf.writeByte(event.getType());
            msg = Unpooled.wrappedBuffer(buf, NettyUtils.writeString(objectMapper.writeValueAsString(event.getSource())));
        } else {
            msg = ctx.alloc().buffer(1);
            msg.writeByte(event.getType());
        }
        out.add(msg);
    }
}
