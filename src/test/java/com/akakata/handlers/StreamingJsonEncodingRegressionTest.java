package com.akakata.handlers;

import com.akakata.event.Event;
import com.akakata.event.Events;
import com.akakata.event.impl.SharedNetworkEvent;
import com.akakata.handlers.codec.EventFrames;
import com.akakata.handlers.codec.JsonEncoder;
import com.akakata.handlers.codec.WebSocketEventEncoder;
import com.akakata.metrics.FlowControlMetrics;
import io.netty.buffer.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies streaming wire compatibility, finite native growth and failure ownership.
 */
class StreamingJsonEncodingRegressionTest {

    /**
     * Creates the streaming codec fixture.
     */
    StreamingJsonEncodingRegressionTest() {
    }

    /**
     * Preserves Jackson wire bytes and application lengths through both transport encoders.
     *
     * @param websocket whether to encode a WebSocket frame instead of TCP application bytes
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unicodeEscapesNestedValuesAndLargePayloadKeepTheirWireBytes(boolean websocket) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("text", "Unicode 中文🙂 and escapes \\\"\n\t");
        source.put("nested", List.of(Map.of("number", 42, "flag", true)));
        source.put("empty", null);
        source.put("payload", "x".repeat(4096));
        byte[] expected = JsonMapper.builder().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS).build()
                .writeValueAsBytes(source);
        TrackingAllocator allocator = new TrackingAllocator();
        EmbeddedChannel channel = new EmbeddedChannel(websocket ? new WebSocketEventEncoder() : new JsonEncoder());
        channel.config().setAllocator(allocator);
        try {
            assertTrue(channel.writeOutbound(Events.networkEvent(source)));
            Object encoded = channel.readOutbound();
            try {
                ByteBuf content = websocket ? ((BinaryWebSocketFrame) encoded).content() : (ByteBuf) encoded;
                assertEquals(Events.NETWORK_MESSAGE, content.readUnsignedByte());
                assertEquals(expected.length, content.readInt());
                byte[] actual = new byte[content.readableBytes()];
                content.readBytes(actual);
                assertArrayEquals(expected, actual);
            } finally {
                ReferenceCountUtil.release(encoded);
            }
            allocator.assertReleased();
        } finally {
            channel.finishAndReleaseAll();
            allocator.releaseRemaining();
        }
    }

    /**
     * Keeps source-less lifecycle messages as a single opcode byte without a JSON length.
     */
    @Test
    void sourceLessControlEventRemainsOneByte() {
        ByteBuf frame = EventFrames.json(UnpooledByteBufAllocator.DEFAULT, Events.event(null, Events.DISCONNECT));
        try {
            assertEquals(1, frame.readableBytes());
            assertEquals(Events.DISCONNECT, frame.readUnsignedByte());
        } finally {
            frame.release();
        }
    }

    /**
     * Accepts the maximum representable frame and rejects the next byte without leaking output.
     *
     * @param direct whether to allocate direct instead of heap buffers
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactFrameBoundaryIsAcceptedAndOverflowReleasesPartialOutput(boolean direct) {
        TrackingAllocator allocator = new TrackingAllocator(direct);
        ByteBuf maximum = EventFrames.json(allocator, Events.networkEvent("x".repeat(65528)));
        try {
            assertEquals(65535, maximum.readableBytes());
            assertEquals(65530, maximum.getInt(1));
            assertTrue(maximum.capacity() <= 65535);
        } finally {
            maximum.release();
        }
        long encoded = FlowControlMetrics.ENCODED_FRAMES.sum();
        assertThrows(TooLongFrameException.class,
                () -> EventFrames.json(allocator, Events.networkEvent("x".repeat(65529))));
        assertEquals(encoded, FlowControlMetrics.ENCODED_FRAMES.sum());
        allocator.assertReleased();
    }

    /**
     * Preserves the protocol overflow cause when a transport encoder wraps the failure.
     *
     * @param websocket whether to use the WebSocket encoder
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transportOverflowPreservesItsCauseAndReturnsNativeOwnership(boolean websocket) {
        TrackingAllocator allocator = new TrackingAllocator();
        EmbeddedChannel channel = new EmbeddedChannel(websocket ? new WebSocketEventEncoder() : new JsonEncoder());
        channel.config().setAllocator(allocator);
        try {
            EncoderException failure = assertThrows(EncoderException.class,
                    () -> channel.writeOutbound(Events.networkEvent("x".repeat(70000))));
            assertInstanceOf(TooLongFrameException.class, failure.getCause());
            assertNull(channel.readOutbound());
            allocator.assertReleased();
        } finally {
            channel.finishAndReleaseAll();
            allocator.releaseRemaining();
        }
    }

    /**
     * Reclaims output written before a getter failure and never caches a failed shared frame.
     */
    @Test
    void partialSerializationFailureReclaimsTheFrameAndSharedOwner() {
        TrackingAllocator allocator = new TrackingAllocator();
        SharedNetworkEvent shared = new SharedNetworkEvent(Events.networkEvent(new PartialFailurePayload()));
        long encoded = FlowControlMetrics.ENCODED_FRAMES.sum();
        try {
            assertThrows(RuntimeException.class, () -> EventFrames.json(allocator, shared));
            assertThrows(RuntimeException.class, () -> EventFrames.json(allocator, shared));
            assertEquals(encoded, FlowControlMetrics.ENCODED_FRAMES.sum());
            allocator.assertReleased();
        } finally {
            shared.releaseOwner();
        }
        assertEquals(0, shared.payloadLease().refCnt());
    }

    /**
     * Emits enough JSON to grow the native frame before an intentionally failing getter.
     */
    @com.fasterxml.jackson.annotation.JsonPropertyOrder({"prefix", "failure"})
    public static class PartialFailurePayload {

        /**
         * Creates the partially serializable payload.
         */
        public PartialFailurePayload() {
        }

        /**
         * Returns data that forces more than the initial output capacity.
         *
         * @return a large prefix string
         */
        public String getPrefix() {
            return "x".repeat(16000);
        }

        /**
         * Fails after the prefix has been serialized.
         *
         * @return no value because the getter always fails
         * @throws IllegalStateException always, to verify partial-output cleanup
         */
        public String getFailure() {
            throw new IllegalStateException("Injected failure after partial JSON output");
        }
    }

    /**
     * Tracks native frame owners without changing their reference-counting behavior.
     */
    private static final class TrackingAllocator extends AbstractByteBufAllocator {

        /**
         * Buffers allocated by this fixture.
         */
        private final List<ByteBuf> buffers = new ArrayList<>();

        /**
         * Creates a heap-preferring tracking allocator.
         */
        private TrackingAllocator() {
            this(false);
        }

        /**
         * Creates a tracking allocator with explicit native storage preference.
         *
         * @param direct whether to prefer direct buffers
         */
        private TrackingAllocator(boolean direct) {
            super(direct);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
            ByteBuf buffer = new UnpooledHeapByteBuf(this, initialCapacity, maxCapacity);
            buffers.add(buffer);
            return buffer;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
            ByteBuf buffer = new UnpooledDirectByteBuf(this, initialCapacity, maxCapacity);
            buffers.add(buffer);
            return buffer;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean isDirectBufferPooled() {
            return false;
        }

        /**
         * Checks that all tracked native owners have been released.
         */
        private void assertReleased() {
            for (ByteBuf buffer : buffers) {
                assertEquals(0, buffer.refCnt());
            }
        }

        /**
         * Releases any remaining fixture owner after a failed assertion.
         */
        private void releaseRemaining() {
            for (ByteBuf buffer : buffers) {
                if (buffer.refCnt() > 0) {
                    buffer.release(buffer.refCnt());
                }
            }
        }
    }
}
