package com.akakata.communication.impl;

import com.akakata.communication.MessageSender;
import com.akakata.event.Event;
import com.akakata.event.Events;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
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
        Event event = Events.event(null, Events.DISCONNECT);
        if (channel.isActive()) {
            channel.writeAndFlush(event).addListener(ChannelFutureListener.CLOSE);
        } else {
            channel.close();
            LOG.trace("Unable to write the Event {} with type {} to socket", event, event.getType());
        }
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
}
