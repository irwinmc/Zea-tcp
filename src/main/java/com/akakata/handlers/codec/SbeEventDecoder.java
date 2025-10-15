package com.akakata.handlers.codec;

import com.akakata.event.Events;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import org.agrona.concurrent.UnsafeBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 基于 SBE 头格式（blockLength/templateId/schemaId/version）的事件解码器。
 * 长度帧由前置的 {@link io.netty.handler.codec.LengthFieldBasedFrameDecoder} 负责拆包。
 */
@Sharable
public class SbeEventDecoder extends MessageToMessageDecoder<ByteBuf> {

    private static final Logger LOG = LoggerFactory.getLogger(SbeEventDecoder.class);

    private static final int HEADER_SIZE = 8;
    private static final int SCHEMA_ID = 1;
    private static final int VERSION = 1;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        if (msg.readableBytes() < HEADER_SIZE) {
            LOG.warn("Invalid SBE frame, expected header size {}, actual {}", HEADER_SIZE, msg.readableBytes());
            return;
        }

        int blockLength = msg.readUnsignedShortLE();
        int templateId = msg.readUnsignedShortLE();
        int schemaId = msg.readUnsignedShortLE();
        int version = msg.readUnsignedShortLE();

        if (schemaId != SCHEMA_ID || version != VERSION) {
            LOG.debug("Schema/version mismatch. expected {}/{}, actual {}/{}", SCHEMA_ID, VERSION, schemaId, version);
        }

        int available = msg.readableBytes();
        if (blockLength > available) {
            LOG.warn("Declared blockLength {} exceeds remaining bytes {}. Truncating.", blockLength, available);
            blockLength = available;
        }

        ByteBuf payload = msg.readRetainedSlice(blockLength);
        out.add(Events.event(payload, templateId & 0xFF));
    }
}
