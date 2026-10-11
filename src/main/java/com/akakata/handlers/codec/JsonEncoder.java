package com.akakata.handlers.codec;

import com.akakata.event.Event;
import com.akakata.util.NettyUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.util.List;

/**
 * Encodes an event as an opcode followed by a length-prefixed JSON payload.
 *
 * <p>Serialization completes before allocating the opcode buffer so failures do
 * not leave that allocation unreleased.
 *
 * @author Kelvin
 */
@Sharable
public class JsonEncoder extends MessageToMessageEncoder<Event> {

    /**
     * Creates a json encoder instance.
     */
    public JsonEncoder() {
    }

    /**
     * JSON mapper configured for this codec.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS).build();

    /**
     * {@inheritDoc}
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, Event event, List<Object> out) throws Exception {
        ByteBuf msg;
        if (event.getSource() != null) {
            String source = objectMapper.writeValueAsString(event.getSource());
            ByteBuf buf = ctx.alloc().buffer(1);
            buf.writeByte(event.getType());
            msg = Unpooled.wrappedBuffer(buf, NettyUtils.writeString(source));
        } else {
            msg = ctx.alloc().buffer(1);
            msg.writeByte(event.getType());
        }
        out.add(msg);
    }
}
