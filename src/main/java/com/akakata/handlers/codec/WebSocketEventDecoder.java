package com.akakata.handlers.codec;

import com.akakata.event.Events;
import com.akakata.util.NettyUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

import java.util.HashMap;
import java.util.List;

/**
 * @author Kelvin
 */
@Sharable
public class WebSocketEventDecoder extends MessageToMessageDecoder<BinaryWebSocketFrame> {

    private final TypeReference<HashMap<String, Object>> typeRef = new TypeReference<HashMap<String, Object>>() {
    };
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void decode(ChannelHandlerContext ctx, BinaryWebSocketFrame frame, List<Object> out) throws Exception {
        ByteBuf msg = frame.content();

        int opcode = msg.readUnsignedByte();
        if (opcode == Events.NETWORK_MESSAGE) {
            opcode = Events.SESSION_MESSAGE;
        }

        ByteBuf data = msg.readBytes(msg.readableBytes());
        HashMap<String, Object> source = objectMapper.readValue(NettyUtils.readString(data), typeRef);
        data.release();

        out.add(Events.event(source, opcode));
    }
}
