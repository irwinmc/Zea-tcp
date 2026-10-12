package com.akakata.handlers.codec;

import com.akakata.event.Event;
import com.akakata.event.impl.SharedNetworkEvent;
import com.akakata.metrics.FlowControlMetrics;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.TooLongFrameException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

/**
 * Builds bounded application frames without UTF-8 String round trips or composite allocations.
 * Shared Game sends cache one frame per format while preserving recipient reader indices.
 */
public final class EventFrames {

    /**
     * Thread-safe JSON mapper shared by TCP, WebSocket and binary fallback encodings.
     */
    private static final ObjectMapper MAPPER = JsonMapper.builder().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS).build();

    /**
     * Prevents construction of this encoding utility.
     */
    private EventFrames() {}

    /**
     * Encodes an opcode with an optional length-prefixed JSON object.
     *
     * @param allocator the recipient's pooled allocator
     * @param event immutable event metadata and source
     * @return caller-owned application frame excluding transport headers
     */
    public static ByteBuf json(ByteBufAllocator allocator, Event event) {
        if (event instanceof SharedNetworkEvent shared)
            return shared.encodedFrame("JSON", () -> encodeJson(allocator, event));
        return encodeJson(allocator, event);
    }

    /**
     * Builds one canonical JSON frame before any native allocation on serialization failure.
     *
     * @param allocator the native allocator
     * @param event the source event
     * @return newly allocated application frame
     */
    private static ByteBuf encodeJson(ByteBufAllocator allocator, Event event) {
        byte[] payload = event.getSource() == null ? null : MAPPER.writeValueAsBytes(event.getSource());
        int size = payload == null ? 1 : 5 + payload.length;
        validateSize(size);
        ByteBuf frame = allocator.buffer(size, size);
        frame.writeByte(event.getType());
        if (payload != null) frame.writeInt(payload.length).writeBytes(payload);
        FlowControlMetrics.ENCODED_FRAMES.increment();
        return frame;
    }

    /**
     * Encodes the project's eight-byte little-endian binary header and payload.
     *
     * @param allocator the recipient's pooled allocator
     * @param event immutable event metadata and source
     * @return caller-owned SBE-style frame excluding the TCP length prefix
     */
    public static ByteBuf sbe(ByteBufAllocator allocator, Event event) {
        if (event instanceof SharedNetworkEvent shared)
            return shared.encodedFrame("SBE", () -> encodeSbe(allocator, event));
        return encodeSbe(allocator, event);
    }

    /**
     * Builds one canonical binary frame without advancing a native source reader index.
     *
     * @param allocator the native allocator
     * @param event the source event
     * @return newly allocated application frame
     */
    private static ByteBuf encodeSbe(ByteBufAllocator allocator, Event event) {
        Object source = event.getSource();
        ByteBuf nativeSource = source instanceof ByteBuf buffer ? buffer : null;
        byte[] payload = nativeSource != null || source == null ? null
                : source instanceof byte[] bytes ? bytes
                : source instanceof CharSequence text ? text.toString().getBytes(StandardCharsets.UTF_8)
                : MAPPER.writeValueAsBytes(source);
        int length = nativeSource != null ? nativeSource.readableBytes() : payload == null ? 0 : payload.length;
        validateSize(8 + length);
        if (event.getType() < 0 || event.getType() > 255) throw new IllegalArgumentException("Invalid event opcode");
        ByteBuf frame = allocator.buffer(8 + length, 8 + length);
        frame.writeShortLE(length).writeShortLE(event.getType()).writeShortLE(1).writeShortLE(1);
        if (nativeSource != null) frame.writeBytes(nativeSource, nativeSource.readerIndex(), length);
        else if (payload != null) frame.writeBytes(payload);
        FlowControlMetrics.ENCODED_FRAMES.increment();
        return frame;
    }

    /**
     * Rejects frames that cannot be represented by the unsigned two-byte TCP prefix.
     *
     * @param bytes the total application frame length
     * @throws TooLongFrameException if the frame exceeds 65535 bytes
     */
    private static void validateSize(int bytes) {
        if (bytes < 0 || bytes > 65_535) throw new TooLongFrameException("Application frame exceeds 65535 bytes");
    }
}
