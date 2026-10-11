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
 * Decodes the project's eight-byte little-endian SBE-style header and binary payload.
 *
 * <p>The header contains block length, template identifier, schema identifier and
 * version as unsigned 16-bit values. The template identifier supplies the event
 * opcode. Short headers are discarded and oversized declared payloads are truncated
 * to the bytes available in the frame.
 */
@Sharable
public class SbeEventDecoder extends MessageToMessageDecoder<ByteBuf> {

    /**
     * Creates a sbe event decoder instance.
     */
    public SbeEventDecoder() {
    }

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(SbeEventDecoder.class);

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
