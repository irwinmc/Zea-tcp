package com.akakata.communication;

/**
 * Sends outbound messages and releases the underlying transport when closed.
 *
 * @author Kyia
 */
public interface MessageSender {

    /**
     * Submits a message to the transport for outbound delivery.
     *
     * @param message the message or string to send
     * @return an implementation-specific completion handle or result
     */
    Object sendMessage(Object message);

    /**
     * Releases the sender's transport resources.
     */
    void close();
}
