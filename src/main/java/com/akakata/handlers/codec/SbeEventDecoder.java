package com.akakata.handlers.codec;

import com.akakata.event.Events;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

/**
 * Decodes the project's eight-byte little-endian SBE-style header and binary payload.
 *
 * <p>The header contains block length, template identifier, schema identifier and
 * version as unsigned 16-bit values. The template identifier supplies the event
 * opcode. Malformed lengths, unsupported schema versions and opcodes outside the
 * unsigned byte range fail decoding; no partial payload is published.
 */
@Sharable
public class SbeEventDecoder extends MessageToMessageDecoder<ByteBuf> {

    /**
     * Creates a sbe event decoder instance.
     */
    public SbeEventDecoder() {
    }

    /**
     * Size of the SBE-style message header in bytes.
     */
    private static final int HEADER_SIZE = 8;

    /**
     * Schema identifier encoded in the SBE-style header.
     */
    private static final int SCHEMA_ID = 1;

    /**
     * Protocol schema version or application version identifier.
     */
    private static final int VERSION = 1;

    /**
     * {@inheritDoc}
     */
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        if (msg.readableBytes() < HEADER_SIZE) {
            throw new CorruptedFrameException("Truncated SBE header");
        }
        int blockLength = msg.readUnsignedShortLE();
        int templateId = msg.readUnsignedShortLE();
        int schemaId = msg.readUnsignedShortLE();
        int version = msg.readUnsignedShortLE();
        if (schemaId != SCHEMA_ID || version != VERSION || templateId > 255
                || blockLength != msg.readableBytes()) {
            throw new CorruptedFrameException("Invalid SBE length, opcode, schema or version");
        }
        out.add(Events.event(msg.readRetainedSlice(blockLength), templateId));
    }
}
