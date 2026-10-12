package com.akakata.handlers.codec;

import com.akakata.event.Event;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.util.List;

/**
 * Encodes events as bounded JSON application frames.
 *
 * <p>Shared Game sends reuse a canonical read-only application frame with independent
 * recipient indices. Ordinary sends stream JSON into a bounded native frame without
 * a whole-payload heap array or String conversion. Failed partial output is released.
 */
@Sharable
public class JsonEncoder extends MessageToMessageEncoder<Event> {

    /**
     * Creates a sharable event encoder.
     */
    public JsonEncoder() {}

    /**
     * {@inheritDoc}
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, Event event, List<Object> out) throws Exception {
        out.add(EventFrames.json(ctx.alloc(), event));
    }
}
