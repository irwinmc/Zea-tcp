package com.akakata.handlers.codec;

import com.akakata.event.Event;
import com.akakata.event.Events;
import com.akakata.util.NettyUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;

/**
 * Decodes an opcode and a length-prefixed JSON object into an event.
 *
 * <p>Payload bytes are borrowed from the inbound frame during parsing. No copied
 * native buffer survives successful or failed deserialization.
 *
 * @author Kelvin
 */
@Sharable
public class JsonDecoder extends MessageToMessageDecoder<ByteBuf> {

    /**
     * Creates a json decoder instance.
     */
    public JsonDecoder() {
    }

    /**
     * Generic type information for decoding JSON object maps.
     */
    private final TypeReference<HashMap<String, Object>> typeRef = new TypeReference<HashMap<String, Object>>() {
    };

    /**
     * JSON mapper configured for this codec.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS).build();

    /**
     * Parses an opcode and length-prefixed JSON map from a borrowed inbound frame.
     *
     * <p>The collection overload appends the decoded event. Neither overload retains
     * a native payload beyond JSON parsing.
     *
     * @param ctx the Netty pipeline context
     * @param msg the message or buffer being processed
     * @param out the output collection or destination buffer
     * @throws IOException if JSON deserialization fails
     * @throws Exception if the operation fails
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        out.add(decode(ctx, msg));
    }

    /**
     * Parses an opcode and length-prefixed JSON map from a borrowed inbound frame.
     *
     * <p>The collection overload appends the decoded event. Neither overload retains
     * a native payload beyond JSON parsing.
     *
     * @param ctx the Netty pipeline context
     * @param msg the message or buffer being processed
     * @return the decoded event
     * @throws IOException if JSON deserialization fails
     */
    public Event decode(ChannelHandlerContext ctx, ByteBuf msg) throws IOException {
        int opcode = msg.readUnsignedByte();

        // Borrow the input slice; MessageToMessageDecoder releases the parent even on parse failure.
        ByteBuf data = msg.readSlice(msg.readableBytes());
        HashMap<String, Object> source = objectMapper.readValue(NettyUtils.readString(data), typeRef);

        return Events.event(source, opcode);
    }
}
