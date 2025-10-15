package com.akakata.communication.impl;

import com.akakata.communication.MessageSender;
import com.akakata.event.Event;
import com.akakata.event.Events;
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
 * @author Kelvin
 */
public class SocketMessageSender implements MessageSender {

    private static final Logger LOG = LoggerFactory.getLogger(SocketMessageSender.class);

    private final Channel channel;

    public SocketMessageSender(Channel channel) {
        super();
        this.channel = channel;
    }

    @Override
    public Object sendMessage(Object message) {
        return channel.writeAndFlush(message);
    }

    public Channel getChannel() {
        return channel;
    }

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
