package com.akakata.event.impl;

import com.akakata.communication.MessageSender;
import com.akakata.event.ConnectEvent;
import com.akakata.event.Events;

/**
 * Connect事件中Source就是Sender
 *
 * @author Kelvin
 */
public class DefaultConnectEvent extends DefaultEvent implements ConnectEvent {

    private static final long serialVersionUID = 1L;

    protected MessageSender sender;

    public DefaultConnectEvent(MessageSender sender) {
        this.sender = sender;
    }

    @Override
    public int getType() {
        return Events.CONNECT;
    }

    @Override
    public void setType(int type) {
        throw new UnsupportedOperationException("Type field is final, it cannot be reset");
    }

    @Override
    public MessageSender getSource() {
        return sender;
    }

    @Override
    public void setSource(Object source) {
        this.sender = (MessageSender) source;
    }

    @Override
    public MessageSender getSender() {
        return sender;
    }

    @Override
    public void setSender(MessageSender sender) {
        this.sender = sender;
    }
}
