package com.akakata.protocols;

import com.akakata.app.PlayerSession;

/**
 * Names a network protocol and installs its handlers on a player session's transport pipeline.
 *
 * @author Kelvin
 * @see AbstractNettyProtocol
 * @see com.akakata.app.PlayerSession
 */
public interface Protocol {

    /**
     * Returns the protocol's diagnostic name.
     *
     * @return the protocol name
     */
    String getProtocolName();

    /**
     * Installs protocol handlers on the session's socket pipeline.
     *
     * <p>The overload with a clearing flag removes existing handlers first when the
     * flag is true. Installing a protocol requires a socket-backed sender.
     *
     * @param playerSession the player session to configure with this protocol
     * @see io.netty.channel.ChannelPipeline
     */
    void applyProtocol(PlayerSession playerSession);

    /**
     * Installs protocol handlers on the session's socket pipeline.
     *
     * <p>The overload with a clearing flag removes existing handlers first when the
     * flag is true. Installing a protocol requires a socket-backed sender.
     *
     * @param playerSession the player session to configure with this protocol
     * @param clearExistingProtocolHandlers if true, removes all existing handlers from the pipeline
     * before applying this protocol; if false, adds handlers to the existing pipeline
     */
    void applyProtocol(PlayerSession playerSession, boolean clearExistingProtocolHandlers);
}
