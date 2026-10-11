package com.akakata.handlers.codec;

import com.akakata.event.Event;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Encodes events using an eight-byte little-endian SBE-style header.
 *
 * <p>Native byte-buffer payloads are copied without advancing their reader index;
 * character sequences use UTF-8 and other values use JSON with a string fallback.
 */
@Sharable
public class SbeEventEncoder extends MessageToMessageEncoder<Event> {

    /**
     * Creates a sbe event encoder instance.
     */
    public SbeEventEncoder() {
    }

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(SbeEventEncoder.class);

    /**
     * Size of the SBE-style message header in bytes.
     */
    private static final int HEADER_SIZE = 8;

    /**
     * Schema identifier encoded in the SBE-style header.
     */
    private static final int SCHEMA_ID = 1;

    /**
     * Protocol schema version or application version identifier.
     */
    private static final int VERSION = 1;

    /**
     * Shared JSON mapper for payload conversion.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS).build();

    /**
     * {@inheritDoc}
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, Event event, List<Object> out) throws Exception {
        byte[] payload = resolvePayload(event);
        ByteBuf encoded = encodeFrame(ctx.alloc(), event.getType(), payload);
        out.add(encoded);
    }

    /**
     * Converts an event source to bytes without advancing a native source's reader index.
     *
     * @param event the event to process
     * @return the source bytes, or an empty array for null sources
     */
    private byte[] resolvePayload(Event event) {
        Object source = event.getSource();
        if (source == null) {
            return new byte[0];
        }
        if (source instanceof ByteBuf buf) {
            int len = buf.readableBytes();
            byte[] data = new byte[len];
            buf.getBytes(buf.readerIndex(), data);
            return data;
        }
        if (source instanceof CharSequence sequence) {
            return sequence.toString().getBytes(StandardCharsets.UTF_8);
        }
        try {
            return MAPPER.writeValueAsBytes(source);
        } catch (JacksonException e) {
            LOG.warn("Failed to serialize event source with JSON, fallback to toString()", e);
            return source.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Allocates and fills the eight-byte little-endian event header and payload.
     *
     * @param allocator the native buffer allocator
     * @param eventType the event opcode or subscription selector
     * @param payload the encoded or captured payload
     * @return the caller-owned encoded frame
     */
    private ByteBuf encodeFrame(ByteBufAllocator allocator, int eventType, byte[] payload) {
        ByteBuf buffer = allocator.buffer(HEADER_SIZE + payload.length);
        buffer.writeShortLE(payload.length);
        buffer.writeShortLE(eventType & 0xFFFF);
        buffer.writeShortLE(SCHEMA_ID);
        buffer.writeShortLE(VERSION);
        buffer.writeBytes(payload);
        return buffer;
    }
}
