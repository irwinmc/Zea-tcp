package com.akakata.handlers.codec;

import com.akakata.event.Event;
import com.akakata.event.Events;
import com.akakata.util.NettyUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

/**
 * @author Kelvin
 */
@Sharable
public class JsonDecoder extends MessageToMessageDecoder<ByteBuf> {

    private final TypeReference<HashMap<String, Object>> typeRef = new TypeReference<HashMap<String, Object>>() {
    };
    private final ObjectMapper objectMapper = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        out.add(decode(ctx, msg));
    }

    public Event decode(ChannelHandlerContext ctx, ByteBuf msg) throws IOException {
        int opcode = msg.readUnsignedByte();

        ByteBuf data = msg.readBytes(msg.readableBytes());
        HashMap<String, Object> source = objectMapper.readValue(NettyUtils.readString(data), typeRef);
        data.release();

        return Events.event(source, opcode);
    }
}
