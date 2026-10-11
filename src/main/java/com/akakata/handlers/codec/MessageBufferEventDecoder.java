package com.akakata.handlers.codec;

import com.akakata.communication.impl.NettyMessageBuffer;
import com.akakata.event.Event;
import com.akakata.event.Events;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

/**
 * Decodes an opcode and copies the payload into a Netty-backed message-buffer event.
 *
 * <p>The output owns the newly allocated payload reference. Inbound network-message
 * opcodes are normalized to session-message opcodes.
 *
 * @author Kelvin
 */
@Sharable
public class MessageBufferEventDecoder extends MessageToMessageDecoder<ByteBuf> {

    /**
     * Creates a message buffer event decoder instance.
     */
    public MessageBufferEventDecoder() {
    }

    /**
     * Extracts an opcode and wraps a newly allocated payload copy in a message buffer.
     *
     * @param ctx the Netty pipeline context
     * @param msg the message or buffer being processed
     * @param out the output collection or destination buffer
     * @throws Exception if the operation fails
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        out.add(decode(ctx, msg));
    }

    /**
     * Extracts an opcode and wraps a newly allocated payload copy in a message buffer.
     *
     * @param ctx the Netty pipeline context
     * @param msg the message or buffer being processed
     * @return the event owning the copied payload reference
     */
    public Event decode(ChannelHandlerContext ctx, ByteBuf msg) {
        int opcode = msg.readUnsignedByte();
        if (opcode == Events.NETWORK_MESSAGE) {
            opcode = Events.SESSION_MESSAGE;
        }
        ByteBuf data = msg.readBytes(msg.readableBytes());
        return Events.event(new NettyMessageBuffer(data), opcode);
    }
}
