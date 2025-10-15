package com.akakata.event.impl;

import com.akakata.event.Event;
import com.akakata.event.Events;
import com.akakata.event.NetworkEvent;
import io.netty.channel.Channel;

/**
 * Default implementation of NetworkEvent interface. This class wraps a message that
 * needs to be transmitted to a remote Machine or VM.
 *
 * @author Kyia
 */
public class DefaultNetworkEvent extends DefaultEvent implements NetworkEvent {

    private static final long serialVersionUID = -4779264464285216014L;

    private Channel channel;

    /**
     * Default constructor which will set the ConnectStrategy to HOLD.
     * It will also set the type of the event to NETWORK_MESSAGE
     */
    public DefaultNetworkEvent() {
        super.setType(Events.NETWORK_MESSAGE);
    }

    /**
     * Copy constructor which will take values from the event and set it on
     * this instance. It will disregard the type of the event and set it to
     * NETWORK_MESSAGE, ConnectStrategy to HOLD
     *
     * @param event
     */
    public DefaultNetworkEvent(Event event) {
        this(event, null);
    }

    /**
     * Copy constructor which will take values from the event and set it on
     * this instance. It will disregard the type of the event and set it to
     * NETWORK_MESSAGE, to the value passed in
     *
     * @param event
     * @param channel
     */
    public DefaultNetworkEvent(Event event, Channel channel) {
        this.setSource(event.getSource());
        this.setTimeStamp(event.getTimeStamp());
        this.channel = channel;
        super.setType(Events.NETWORK_MESSAGE);
    }

    @Override
    public void setType(int type) {
        throw new IllegalArgumentException("Event type of this class is already set to NETWORK_MESSAGE. It should not be reset.");
    }


}
