package com.akakata.handlers.codec;

import com.akakata.event.Events;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

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
     * Shared strict JSON application-frame decoder.
     */
    private final JsonDecoder decoder = new JsonDecoder();

    /**
     * {@inheritDoc}
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, BinaryWebSocketFrame frame, List<Object> out) throws Exception {
        if (!frame.isFinalFragment()) {
            throw new CorruptedFrameException("Fragmented application messages are unsupported");
        }
        com.akakata.event.Event event = decoder.decode(ctx, frame.content());
        if (event.getType() == Events.NETWORK_MESSAGE) event.setType(Events.SESSION_MESSAGE);
        out.add(event);
    }
}
