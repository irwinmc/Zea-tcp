package com.akakata.communication;

/**
 * This interface declares method for sending a message to client. Different
 * implementations would be used by the server for sending based on the connect
 * strategy that is required.
 *
 * @author Kyia
 */
public interface MessageSender {

    /**
     * This method delegates to the underlying native session object to send a
     * message to the client.
     *
     * @param message message
     * @return Object been sent
     */
    Object sendMessage(Object message);

    /**
     * Since message sender would have a network connection, it would require
     * some cleanup. This method can be overriden to close underlying channels
     * and so on.
     */
    void close();
}
