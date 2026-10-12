package com.akakata.protocols;

import com.akakata.app.PlayerSession;
import com.akakata.handlers.TransportLifecycleHandler;
import com.akakata.util.NettyUtils;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * Provides a protocol name, two-byte TCP frame decoding and optional pipeline replacement.
 *
 * @author Kelvin
 * @see Protocol
 * @see LengthFieldBasedFrameDecoder
 */
public abstract class AbstractNettyProtocol implements Protocol {

    /**
     * Diagnostic name identifying this protocol.
     */
    final String protocolName;

    /**
     * Constructs a new Netty protocol with the specified name.
     *
     * @param protocolName the unique identifier for this protocol
     */
    public AbstractNettyProtocol(String protocolName) {
        super();
        this.protocolName = protocolName;
    }

    /**
     * Creates an inbound decoder for a two-byte length prefix, stripping that prefix from decoded
     * frames.
     *
     * @return a new frame decoder
     * @see LengthFieldBasedFrameDecoder
     */
    public LengthFieldBasedFrameDecoder createLengthBasedFrameDecoder() {
        return new LengthFieldBasedFrameDecoder(65_537, 0, 2, 0, 2);
    }

    /**
     * Creates configured TCP framing while retaining the unsigned two-byte wire prefix.
     *
     * @param pipeline the socket pipeline carrying its persistent transport policy
     * @return a decoder bounded by application bytes plus the two-byte prefix
     */
    protected LengthFieldBasedFrameDecoder createLengthBasedFrameDecoder(ChannelPipeline pipeline) {
        int maximum = TransportLifecycleHandler.maximumFrameBytes(pipeline);
        return new LengthFieldBasedFrameDecoder(maximum + 2, 0, 2, 0, 2);
    }

    /**
     * Returns the protocol's diagnostic name.
     *
     * @return the protocol name
     */
    @Override
    public String getProtocolName() {
        return protocolName;
    }

    /**
     * Installs protocol handlers on the session's socket pipeline.
     *
     * <p>The overload with a clearing flag removes existing handlers first when the
     * flag is true while preserving installed transport lifetime guards. Installing
     * a protocol requires a socket-backed sender.
     *
     * @param playerSession                 the player session to configure
     * @param clearExistingProtocolHandlers if true, removes existing protocol handlers before applying
     *                                      protocol
     */
    @Override
    public void applyProtocol(PlayerSession playerSession, boolean clearExistingProtocolHandlers) {
        if (clearExistingProtocolHandlers) {
            ChannelPipeline pipeline = NettyUtils.getPipeLineOfConnection(playerSession);
            NettyUtils.clearPipeline(pipeline);
        }
        applyProtocol(playerSession);
    }
}
