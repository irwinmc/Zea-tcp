package com.akakata.handlers.codec;

import com.akakata.communication.MessageBuffer;
import com.akakata.event.Event;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Encodes event opcodes and Netty-backed message-buffer payloads.
 *
 * <p>Each write uses a retained duplicate so sending a shared event to one channel
 * does not consume another channel's payload or reader index.
 *
 * @author Kelvin
 */
@Sharable
public class MessageBufferEventEncoder extends MessageToMessageEncoder<Event> {

    /**
     * Creates a message buffer event encoder instance.
     */
    public MessageBufferEventEncoder() {
    }

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(MessageBufferEventEncoder.class);

    /**
     * Combines the opcode with a retained duplicate of the native message-buffer payload.
     *
     * @param ctx the Netty pipeline context
     * @param event the event to process
     * @param out the output collection or destination buffer
     * @throws Exception if the operation fails
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, Event event, List<Object> out) throws Exception {
        out.add(encode(ctx, event));
    }

    /**
     * Combines the opcode with a retained duplicate of the native message-buffer payload.
     *
     * @param ctx the Netty pipeline context
     * @param event the event to process
     * @return the encoded buffer whose reference is transferred to the pipeline or caller
     */
    @SuppressWarnings("unchecked")
    protected ByteBuf encode(ChannelHandlerContext ctx, Event event) {
        ByteBuf msg = null;
        if (event.getSource() != null) {
            MessageBuffer<ByteBuf> msgBuffer = (MessageBuffer<ByteBuf>) event.getSource();
            ByteBuf data = msgBuffer.getNativeBuffer().retainedDuplicate();
            ByteBuf opcode = null;
            try {
                opcode = ctx.alloc().buffer(1);
                opcode.writeByte(event.getType());
                msg = Unpooled.wrappedBuffer(opcode, data);
            } catch (Throwable failure) {
                data.release();
                if (opcode != null) {
                    opcode.release();
                }
                throw failure;
            }
        } else {
            msg = ctx.alloc().buffer(1);
            msg.writeByte(event.getType());
        }
        return msg;
    }
}
