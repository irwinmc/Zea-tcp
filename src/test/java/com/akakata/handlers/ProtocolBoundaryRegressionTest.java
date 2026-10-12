package com.akakata.handlers;

import com.akakata.event.Event;
import com.akakata.event.Events;
import com.akakata.handlers.codec.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rejects malformed application frames before partial events or native references escape.
 */
class ProtocolBoundaryRegressionTest {

    /**
     * Creates the protocol fixture.
     */
    ProtocolBoundaryRegressionTest() {}

    /**
     * Rejects short headers, mismatched lengths, schema versions and aliased opcodes.
     *
     * @param invalid the malformed header case
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void sbeRejectsMalformedHeadersWithoutRetainingInput(int invalid) {
        EmbeddedChannel channel = new EmbeddedChannel(new SbeEventDecoder());
        ByteBuf frame = Unpooled.buffer();
        if (invalid == 0) frame.writeZero(7);
        else frame.writeShortLE(invalid == 1 ? 20 : invalid == 2 ? 0 : 1)
                .writeShortLE(invalid == 5 ? 0x133 : Events.SESSION_MESSAGE)
                .writeShortLE(invalid == 3 ? 99 : 1).writeShortLE(invalid == 4 ? 99 : 1).writeByte(1);
        try {
            assertThrows(DecoderException.class, () -> channel.writeInbound(frame));
            assertEquals(0, frame.refCnt());
            assertNull(channel.readInbound());
        } finally { channel.finishAndReleaseAll(); }
    }

    /**
     * Rejects invalid JSON lengths, trailing values and non-object roots over both transports.
     *
     * @param websocket whether to decode WebSocket rather than TCP application frames
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void jsonRejectsMalformedLengthsAndRoots(boolean websocket) {
        for (String json : new String[]{"{} {}", "[]", "null", "{", "{}"}) {
            EmbeddedChannel channel = websocket ? new EmbeddedChannel(new WebSocketEventDecoder()) : new EmbeddedChannel(new JsonDecoder());
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            ByteBuf frame = Unpooled.buffer().writeByte(Events.SESSION_MESSAGE)
                    .writeInt(json.equals("{}") ? -1 : bytes.length).writeBytes(bytes);
            try {
                assertThrows(DecoderException.class, () -> channel.writeInbound(websocket ? new BinaryWebSocketFrame(frame) : frame));
                assertEquals(0, frame.refCnt());
                assertNull(channel.readInbound());
            } finally { channel.finishAndReleaseAll(); }
        }
    }

    /**
     * Rejects short, oversized and trailing JSON inner lengths before retaining the frame.
     *
     * @param length the invalid declared payload length
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, -1})
    void jsonRejectsEveryInnerLengthMismatch(int length) {
        EmbeddedChannel channel = new EmbeddedChannel(new JsonDecoder());
        ByteBuf frame = Unpooled.buffer().writeByte(Events.SESSION_MESSAGE).writeInt(length).writeByte('{').writeByte('}');
        try {
            assertThrows(DecoderException.class, () -> channel.writeInbound(frame));
            assertEquals(0, frame.refCnt());
            assertNull(channel.readInbound());
        } finally { channel.finishAndReleaseAll(); }
    }

    /**
     * Rejects a decoded internal TCP opcode and releases its retained binary payload.
     */
    @Test
    void sbeInternalNetworkOpcodeClosesBeforeSessionCallbacksRun() {
        try (var group = new com.akakata.event.impl.ShardedEventDispatcher(2);
             var game = new com.akakata.app.impl.DefaultGame()) {
            var builder = new com.akakata.app.impl.DefaultPlayerSession.PlayerSessionBuilder().game(game);
            builder.eventDispatcher(group);
            var player = builder.build();
            player.setSender(new com.akakata.communication.MessageSender() {
                @Override
                public Object sendMessage(Object message) { throw new AssertionError("Reserved input reached a sender"); }
                @Override
                public void close() {}
            });
            assertTrue(game.connectSession(player)); game.onLogin(player);
            EmbeddedChannel channel = new EmbeddedChannel(new SbeEventDecoder(), new DefaultToServerHandler(player));
            ByteBuf frame = Unpooled.buffer().writeShortLE(1).writeShortLE(Events.NETWORK_MESSAGE)
                    .writeShortLE(1).writeShortLE(1).writeByte(0);
            try {
                channel.writeInbound(frame);
                assertFalse(channel.isOpen());
                assertTrue(player.isShuttingDown());
                assertEquals(0, frame.refCnt());
            } finally { channel.finishAndReleaseAll(); }
        }
    }

    /**
     * Captures decoded payload storage and retains WebSocket opcode compatibility.
     */
    @Test
    void validJsonFramesPreservePayloadAndCaptureByteCharge() {
        EmbeddedChannel channel = new EmbeddedChannel(new WebSocketEventDecoder());
        byte[] bytes = "{\"sequence\":7}".getBytes(StandardCharsets.UTF_8);
        try {
            assertTrue(channel.writeInbound(new BinaryWebSocketFrame(Unpooled.buffer().writeByte(Events.NETWORK_MESSAGE)
                    .writeInt(bytes.length).writeBytes(bytes))));
            Event event = channel.readInbound();
            assertEquals(Events.SESSION_MESSAGE, event.getType());
            assertTrue(event.getPayloadSize() >= bytes.length);
        } finally { channel.finishAndReleaseAll(); }
    }
}
