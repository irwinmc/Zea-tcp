package com.akakata.handlers.codec;

import com.akakata.event.Event;
import com.akakata.event.EventResources;
import com.akakata.event.Events;
import com.akakata.event.impl.DefaultEvent;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.MessageToMessageDecoder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

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
    private final ObjectMapper objectMapper = JsonMapper.builder().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS).enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

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
        if (msg.readableBytes() < 5) {
            throw new CorruptedFrameException("Truncated JSON event header");
        }
        int opcode = msg.readUnsignedByte();
        int length = msg.readInt();
        if (length <= 0 || length != msg.readableBytes()) {
            throw new CorruptedFrameException("Invalid JSON payload length");
        }
        HashMap<String, Object> source = objectMapper.readValue(
                (java.io.InputStream) new ByteBufInputStream(msg.readSlice(length)), typeRef);
        if (source == null) throw new CorruptedFrameException("JSON object required");
        DefaultEvent event = (DefaultEvent) Events.event(source, opcode);
        event.setPayloadSize(Math.max(length, EventResources.estimateBytes(source)));
        return event;
    }
}
