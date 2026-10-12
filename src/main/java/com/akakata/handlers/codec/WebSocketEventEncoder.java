package com.akakata.handlers.codec;

import com.akakata.event.Event;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

import java.util.List;

/**
 * Encodes events as bounded JSON application frames.
 *
 * <p>Shared Game sends reuse a canonical read-only application frame with independent
 * recipient indices. Ordinary sends serialize directly to bytes without String conversion.
 */
@Sharable
public class WebSocketEventEncoder extends MessageToMessageEncoder<Event> {

    /**
     * Creates a sharable event encoder.
     */
    public WebSocketEventEncoder() {}

    /**
     * {@inheritDoc}
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, Event event, List<Object> out) throws Exception {
        out.add(new BinaryWebSocketFrame(EventFrames.json(ctx.alloc(), event)));
    }
}
