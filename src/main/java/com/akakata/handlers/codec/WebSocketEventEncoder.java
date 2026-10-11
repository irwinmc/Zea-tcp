package com.akakata.handlers.codec;

import com.akakata.event.Event;
import com.akakata.util.NettyUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

import java.util.List;

/**
 * Encodes events as binary WebSocket frames containing an opcode and optional JSON payload.
 *
 * @author Kelvin
 */
@Sharable
public class WebSocketEventEncoder extends MessageToMessageEncoder<Event> {

    /**
     * Creates a web socket event encoder instance.
     */
    public WebSocketEventEncoder() {
    }

    /**
     * JSON mapper configured for this codec.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS).build();

    /**
     * {@inheritDoc}
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, Event event, List<Object> out) throws Exception {
        ByteBuf msg;
        if (event.getSource() != null) {
            String source = objectMapper.writeValueAsString(event.getSource());
            ByteBuf buf = ctx.alloc().buffer(1);
            buf.writeByte(event.getType());

            msg = Unpooled.wrappedBuffer(buf, NettyUtils.writeString(source));
        } else {
            msg = ctx.alloc().buffer(1);
            msg.writeByte(event.getType());
        }
        out.add(new BinaryWebSocketFrame(msg));
    }
}
