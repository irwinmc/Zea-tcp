package com.akakata.event;

import com.akakata.communication.MessageSender;

/**
 * @author Kelvin
 */
public interface ConnectEvent extends Event {

    /**
     * Get message sender
     *
     * @return message sender
     */
    MessageSender getSender();

    /**
     * Set message sender
     *
     * @param sender message sender
     */
    void setSender(MessageSender sender);
}
