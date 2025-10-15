package com.akakata.protocols;

import com.akakata.app.PlayerSession;

/**
 * @author Kelvin
 */
public interface Protocol {

    /**
     * Return the string name of this protocol.
     *
     * @return name of the protocol. This will be used in scenarios where a
     * custom protocol has been used.
     */
    String getProtocolName();

    /**
     * The main method of this interface. For the Netty implementation, this
     * will be used to add the handlers in the pipeline associated with this
     * user session. For now, "configuration" only means adding of handlers. It
     * is expected that the loginHandler or whichever previous handler
     * was handling the message has cleared up the ChannelPipeline
     * object.
     *
     * @param playerSession The user session for which the protocol handlers need to be
     *                      set.
     */
    void applyProtocol(PlayerSession playerSession);

    /**
     * This method delegates to the {@link #applyProtocol(PlayerSession)} method
     * after clearing the pipeline based on the input flag.
     *
     * @param playerSession
     * @param clearExistingProtocolHandlers Clears the pipeline of existing protocol handlers if set to
     *                                      true.
     */
    void applyProtocol(PlayerSession playerSession, boolean clearExistingProtocolHandlers);
}
