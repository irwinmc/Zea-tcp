package com.akakata.handlers;

import com.akakata.app.impl.DefaultGame;
import com.akakata.event.Events;
import com.akakata.event.Event;
import com.akakata.handlers.codec.JsonDecoder;
import com.akakata.handlers.codec.JsonEncoder;
import com.akakata.handlers.codec.WebSocketEventDecoder;
import com.akakata.handlers.codec.WebSocketEventEncoder;
import com.akakata.service.LoginService;
import com.akakata.service.SecurityService;
import com.akakata.service.SessionService;
import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.UnpooledDirectByteBuf;
import io.netty.buffer.UnpooledHeapByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies codec round trips and native reference cleanup for malformed input and serialization
 * failures.
 */
class BufferLifecycleRegressionTest {
    /**
     * Creates a buffer lifecycle regression test instance.
     */
    BufferLifecycleRegressionTest() {
    }

    /**
     * Verifies the regression scenario: valid messages round trip and release their buffers.
     *
     * @param websocket whether to exercise WebSocket frames instead of TCP JSON frames
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void validMessagesRoundTripAndReleaseTheirBuffers(boolean websocket) {
        TrackingAllocator allocator = new TrackingAllocator();
        EmbeddedChannel outbound = new EmbeddedChannel(websocket ? new WebSocketEventEncoder() : new JsonEncoder());
        EmbeddedChannel inbound = new EmbeddedChannel(websocket ? new WebSocketEventDecoder() : new JsonDecoder());
        outbound.config().setAllocator(allocator);
        Map<String, Object> payload = Map.of("message", "hello");
        try {
            assertTrue(outbound.writeOutbound(Events.networkEvent(payload)));
            Object encoded = outbound.readOutbound();
            assertTrue(inbound.writeInbound(encoded));
            Event decoded = inbound.readInbound();

            assertEquals(payload, decoded.getSource());
            allocator.assertReleased();
        } finally {
            outbound.finishAndReleaseAll();
            inbound.finishAndReleaseAll();
            allocator.releaseRemaining();
        }
    }

    /**
     * Verifies the regression scenario: malformed json releases all input buffers.
     *
     * @param websocket the websocket
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedJsonReleasesAllInputBuffers(boolean websocket) {
        TrackingAllocator allocator = new TrackingAllocator();
        ChannelHandler decoder = websocket ? new WebSocketEventDecoder() : new JsonDecoder();
        EmbeddedChannel channel = new EmbeddedChannel(decoder);
        try {
            ByteBuf input = allocator.buffer().writeByte(Events.SESSION_MESSAGE).writeInt(1).writeByte('{');

            assertThrows(DecoderException.class, () -> channel.writeInbound(
                    websocket ? new BinaryWebSocketFrame(input) : input));

            allocator.assertReleased();
        } finally {
            channel.finishAndReleaseAll();
            allocator.releaseRemaining();
        }
    }

    /**
     * Verifies the regression scenario: serialization failure does not leak opcode buffer.
     *
     * @param websocket the websocket
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void serializationFailureDoesNotLeakOpcodeBuffer(boolean websocket) {
        TrackingAllocator allocator = new TrackingAllocator();
        ChannelHandler encoder = websocket ? new WebSocketEventEncoder() : new JsonEncoder();
        EmbeddedChannel channel = new EmbeddedChannel(encoder);
        channel.config().setAllocator(allocator);
        try {
            assertThrows(EncoderException.class, () -> channel.writeOutbound(
                    Events.networkEvent(new FailingPayload())));

            allocator.assertReleased();
        } finally {
            channel.finishAndReleaseAll();
            allocator.releaseRemaining();
        }
    }

    /**
     * Verifies the regression scenario: rejected web socket login releases copied payload.
     */
    @Test
    void rejectedWebSocketLoginReleasesCopiedPayload() {
        TrackingAllocator allocator = new TrackingAllocator();
        DefaultGame game = new DefaultGame();
        SessionService sessions = new SessionService(buffer -> null, false);
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocketLoginHandler(null, game,
                new LoginService(sessions, new SecurityService())));
        try {
            ByteBuf input = allocator.buffer().writeByte(Events.SESSION_MESSAGE).writeByte(1);

            channel.writeInbound(new BinaryWebSocketFrame(input));

            assertFalse(channel.isOpen());
            allocator.assertReleased();
        } finally {
            channel.finishAndReleaseAll();
            sessions.close();
            game.close();
            allocator.releaseRemaining();
        }
    }

    /**
     * Throws during JSON serialization to exercise allocation cleanup.
     */
    public static class FailingPayload {
        /**
         * Creates a failing payload instance.
         */
        public FailingPayload() {
        }

        /**
         * Returns the value.
         *
         * @return the value
         */
        public String getValue() {
            throw new IllegalStateException("Injected serialization failure");
        }
    }

    /**
     * Tracks native allocations so tests can assert their final reference counts.
     */
    private static class TrackingAllocator extends AbstractByteBufAllocator {
        /**
         * Buffers used by this regression fixture.
         */
        private final List<ByteBuf> buffers = new ArrayList<>();

        /**
         * Creates a tracking allocator instance.
         */
        TrackingAllocator() { super(false); }

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
        public boolean isDirectBufferPooled() { return false; }

        /**
         * Checks assert released.
         */
        void assertReleased() {
            for (ByteBuf buffer : buffers) {
                assertEquals(0, buffer.refCnt(), "A failed request must release every allocated buffer");
            }
        }

        /**
         * Verifies the regression scenario: release remaining.
         */
        void releaseRemaining() {
            for (ByteBuf buffer : buffers) {
                if (buffer.refCnt() > 0) {
                    buffer.release(buffer.refCnt());
                }
            }
        }
    }
}
