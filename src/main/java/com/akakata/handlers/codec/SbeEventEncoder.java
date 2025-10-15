package com.akakata.handlers.codec;

import com.akakata.event.Event;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 将 {@link Event} 编码为 SBE 头格式 + payload。
 * payload 默认处理 ByteBuf / CharSequence / POJO，其他类型回退到 JSON 序列化。
 */
@Sharable
public class SbeEventEncoder extends MessageToMessageEncoder<Event> {

    private static final Logger LOG = LoggerFactory.getLogger(SbeEventEncoder.class);

    private static final int HEADER_SIZE = 8;
    private static final int SCHEMA_ID = 1;
    private static final int VERSION = 1;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    @Override
    protected void encode(ChannelHandlerContext ctx, Event event, List<Object> out) throws Exception {
        byte[] payload = resolvePayload(event);
        ByteBuf encoded = encodeFrame(ctx.alloc(), event.getType(), payload);
        out.add(encoded);
    }

    private byte[] resolvePayload(Event event) {
        Object source = event.getSource();
        if (source == null) {
            return new byte[0];
        }
        if (source instanceof ByteBuf buf) {
            int len = buf.readableBytes();
            byte[] data = new byte[len];
            buf.getBytes(buf.readerIndex(), data);
            return data;
        }
        if (source instanceof CharSequence sequence) {
            return sequence.toString().getBytes(StandardCharsets.UTF_8);
        }
        try {
            return MAPPER.writeValueAsBytes(source);
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialize event source with JSON, fallback to toString()", e);
            return source.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    private ByteBuf encodeFrame(ByteBufAllocator allocator, int eventType, byte[] payload) {
        ByteBuf buffer = allocator.buffer(HEADER_SIZE + payload.length);
        buffer.writeShortLE(payload.length);
        buffer.writeShortLE(eventType & 0xFFFF);
        buffer.writeShortLE(SCHEMA_ID);
        buffer.writeShortLE(VERSION);
        buffer.writeBytes(payload);
        return buffer;
    }
}
