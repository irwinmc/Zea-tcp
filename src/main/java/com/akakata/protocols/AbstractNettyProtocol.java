package com.akakata.protocols;

import com.akakata.app.PlayerSession;
import com.akakata.util.NettyUtils;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;

/**
 * Abstract base class for Netty-based protocol implementations.
 * <p>
 * This class provides common functionality for protocols that use Netty's channel pipeline
 * architecture, including protocol naming, pipeline clearing, and length-based frame decoding.
 * All protocol implementations that use TCP-based length-prefixed framing should extend this class.
 * </p>
 * <p>
 * <b>Frame Decoding:</b><br>
 * By default, this class provides a length-based frame decoder configured for 2-byte length prefix:
 * <ul>
 *   <li>Max frame length: Integer.MAX_VALUE (2GB)</li>
 *   <li>Length field offset: 0 (length field starts at byte 0)</li>
 *   <li>Length field length: 2 bytes (short)</li>
 *   <li>Length adjustment: 0 (length field contains exact payload size)</li>
 *   <li>Initial bytes to strip: 2 (remove length field from output frame)</li>
 * </ul>
 * </p>
 * <p>
 * <b>Subclass Responsibilities:</b><br>
 * Concrete protocol implementations must:
 * <ul>
 *   <li>Call {@code super(protocolName)} to set the protocol identifier</li>
 *   <li>Implement {@link #applyProtocol(PlayerSession)} to configure pipeline handlers</li>
 *   <li>Add appropriate encoder/decoder handlers in correct order</li>
 * </ul>
 * </p>
 *
 * @author Kelvin
 * @see Protocol
 * @see LengthFieldBasedFrameDecoder
 */
public abstract class AbstractNettyProtocol implements Protocol {

    /**
     * The unique name identifier for this protocol.
     * <p>
     * This is set by the subclass constructor and used for logging and debugging.
     * Common values include "SBE_PROTOCOL", "JSON_PROTOCOL", "WEB_SOCKET_PROTOCOL", etc.
     * </p>
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
     * Creates a standard length-based frame decoder for TCP protocols.
     * <p>
     * This decoder handles variable-length messages with a 2-byte length prefix.
     * The length field indicates the size of the payload (excluding the length field itself),
     * and is automatically stripped from the output frame.
     * </p>
     * <p>
     * <b>Frame Format:</b>
     * <pre>
     * +--------+----------------+
     * | Length | Actual Payload |
     * | 2 bytes|  N bytes       |
     * +--------+----------------+
     * </pre>
     * </p>
     *
     * @return a configured LengthFieldBasedFrameDecoder instance
     * @see LengthFieldBasedFrameDecoder
     */
    public LengthFieldBasedFrameDecoder createLengthBasedFrameDecoder() {
        return new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 2, 0, 2);
    }

    @Override
    public String getProtocolName() {
        return protocolName;
    }

    /**
     * {@inheritDoc}
     * <p>
     * This implementation optionally clears the channel pipeline before delegating
     * to {@link #applyProtocol(PlayerSession)}. Pipeline clearing is useful when
     * switching protocols mid-session (e.g., after authentication completes).
     * </p>
     *
     * @param playerSession                  the player session to configure
     * @param clearExistingProtocolHandlers  if true, removes all existing handlers before applying protocol
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
