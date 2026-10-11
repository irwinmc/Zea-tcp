package com.akakata.communication.impl;

import com.akakata.communication.MessageSender;
import com.akakata.event.Event;
import com.akakata.event.Events;
import com.akakata.event.EventResources;
import com.akakata.handlers.codec.EventEncoder;
import com.akakata.handlers.codec.JsonEncoder;
import com.akakata.handlers.codec.MessageBufferEventEncoder;
import com.akakata.handlers.codec.SbeEventEncoder;
import com.akakata.handlers.codec.WebSocketEventEncoder;
import com.akakata.util.NettyUtils;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.MessageToMessageEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes messages to a Netty channel and closes it after a disconnect message.
 *
 * <p>Event writes retain the native payload until the write future completes.
 * For raw Netty messages, normal pipeline ownership rules apply. Closing selects an
 * event encoder when available and otherwise writes a one-byte disconnect opcode.
 *
 * @author Kelvin
 */
public class SocketMessageSender implements MessageSender {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(SocketMessageSender.class);

    /**
     * Associated transport channel.
     */
    private final Channel channel;

    /**
     * Associates an outbound sender with a Netty channel.
     *
     * @param channel the Netty transport channel
     */
    public SocketMessageSender(Channel channel) {
        super();
        this.channel = channel;
    }

    /**
     * Writes and flushes a message through the channel.
     *
     * <p>For an event, a captured native payload is retained until the write future
     * completes, including failed writes. The caller keeps its original event-payload
     * reference. Raw Netty messages follow normal pipeline ownership transfer.
     *
     * @param message the message or string to send
     * @return the channel write future
     */
    @Override
    public Object sendMessage(Object message) {
        if (message instanceof Event event) {
            Object payloadReference = EventResources.retainPayload(event);
            try {
                return channel.writeAndFlush(message).addListener(future ->
                        EventResources.releasePayload(payloadReference));
            } catch (Throwable failure) {
                EventResources.releasePayload(payloadReference);
                throw failure;
            }
        }
        return channel.writeAndFlush(message);
    }

    /**
     * Returns the associated Netty channel.
     *
     * @return the associated Netty channel
     */
    public Channel getChannel() {
        return channel;
    }

    /**
     * Writes a disconnect opcode or event and closes the channel when that write completes.
     */
    @Override
    public void close() {
        LOG.debug("Going to close tcp connection in class: {}", this.getClass().getName());
        if (!channel.isOpen()) {
            channel.close();
            return;
        }

        Event event = Events.event(null, Events.DISCONNECT);
        if (channel.isActive() && supportsEventOutbound(channel.pipeline())) {
            channel.writeAndFlush(event).addListener(ChannelFutureListener.CLOSE);
            return;
        }

        // Fallback：在缺乏事件编码器的管道（如登录阶段）中，只写入最小长度的断开指令
        channel.writeAndFlush(NettyUtils.createBufferForOpcode(event.getType()))
                .addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        String channelId = "Socket channel: ";
        if (channel != null) {
            channelId += channel.toString();
        } else {
            channelId += "0";
        }
        String sender = "Netty " + channelId;
        return sender;
    }

    /**
     * Tests whether the pipeline contains one of the supported event encoders.
     *
     * @param pipeline the Netty handler pipeline
     * @return {@code true} if an event encoder is present
     */
    private boolean supportsEventOutbound(ChannelPipeline pipeline) {
        if (pipeline == null) {
            return false;
        }
        return pipeline.get(JsonEncoder.class) != null
                || pipeline.get(WebSocketEventEncoder.class) != null
                || pipeline.get(MessageBufferEventEncoder.class) != null
                || pipeline.get(SbeEventEncoder.class) != null
                || pipeline.get(EventEncoder.class) != null
                || hasGenericEventEncoder(pipeline);
    }

    /**
     * Scans pipeline message encoders for a supported event-encoder implementation.
     *
     * @param pipeline the Netty handler pipeline
     * @return {@code true} if a supported message encoder is found
     */
    private boolean hasGenericEventEncoder(ChannelPipeline pipeline) {
        for (String name : pipeline.names()) {
            if (pipeline.get(name) instanceof MessageToMessageEncoder<?> encoder) {
                if (encoder instanceof JsonEncoder
                        || encoder instanceof WebSocketEventEncoder
                        || encoder instanceof MessageBufferEventEncoder
                        || encoder instanceof SbeEventEncoder
                        || encoder instanceof EventEncoder) {
                    return true;
                }
            }
        }
        return false;
    }
}
