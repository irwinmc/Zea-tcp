package com.akakata.handlers.codec;

import com.akakata.event.Events;
import com.akakata.util.NettyUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

import java.util.HashMap;
import java.util.List;

/**
 * Decodes binary WebSocket frames containing an opcode and length-prefixed JSON.
 *
 * <p>Inbound network-message opcodes are normalized to session-message opcodes.
 * JSON parsing borrows frame content, which Netty releases after decoding.
 *
 * @author Kelvin
 */
@Sharable
public class WebSocketEventDecoder extends MessageToMessageDecoder<BinaryWebSocketFrame> {

    /**
     * Creates a web socket event decoder instance.
     */
    public WebSocketEventDecoder() {
    }

    /**
     * Generic type information for decoding JSON object maps.
     */
    private final TypeReference<HashMap<String, Object>> typeRef = new TypeReference<HashMap<String, Object>>() {
    };
    /**
     * JSON mapper configured for this codec.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * {@inheritDoc}
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, BinaryWebSocketFrame frame, List<Object> out) throws Exception {
        ByteBuf msg = frame.content();

        int opcode = msg.readUnsignedByte();
        if (opcode == Events.NETWORK_MESSAGE) {
            opcode = Events.SESSION_MESSAGE;
        }

        // Borrow the frame content rather than allocating a copy that may leak on malformed JSON.
        ByteBuf data = msg.readSlice(msg.readableBytes());
        HashMap<String, Object> source = objectMapper.readValue(NettyUtils.readString(data), typeRef);

        out.add(Events.event(source, opcode));
    }
}
