package com.akakata.protocols;

import com.akakata.app.PlayerSession;

/**
 * Protocol abstraction for configuring communication protocols on player sessions.
 * <p>
 * A protocol defines how messages are encoded, decoded, and processed for a specific
 * communication channel (TCP, WebSocket, HTTP, etc.). Each protocol implementation
 * configures the Netty ChannelPipeline with appropriate handlers for message framing,
 * encoding/decoding, and business logic processing.
 * </p>
 * <p>
 * <b>Supported Protocol Types:</b>
 * <ul>
 *   <li>SbeProtocol - Simple Binary Encoding (SBE) for high-performance binary messages</li>
 *   <li>JsonProtocol - JSON-based messaging for debugging and cross-platform compatibility</li>
 *   <li>WebSocketProtocol - WebSocket frame handling for browser clients</li>
 *   <li>MessageBufferProtocol - Custom binary protocol with length-prefix framing</li>
 *   <li>StringProtocol - Text-based messaging with delimiter framing</li>
 * </ul>
 * </p>
 * <p>
 * <b>Pipeline Configuration:</b><br>
 * Protocols configure the Netty pipeline in a specific order:
 * <pre>
 * Inbound (Client → Server):
 *   FrameDecoder → MessageDecoder → EventHandler
 *
 * Outbound (Server → Client):
 *   EventHandler → MessageEncoder → FramePrepender
 * </pre>
 * </p>
 * <p>
 * <b>Usage Example:</b>
 * <pre>
 * // Apply protocol to a newly connected session
 * Protocol protocol = new SbeProtocol(decoder, encoder, prepender);
 * protocol.applyProtocol(playerSession);
 *
 * // Switch protocol mid-session (e.g., after login)
 * protocol.applyProtocol(playerSession, true); // Clear existing handlers first
 * </pre>
 * </p>
 *
 * @author Kelvin
 * @see AbstractNettyProtocol
 * @see com.akakata.app.PlayerSession
 */
public interface Protocol {

    /**
     * Returns the unique name identifier for this protocol.
     * <p>
     * The protocol name is used for logging, debugging, and distinguishing between
     * different protocol implementations at runtime.
     * </p>
     *
     * @return the protocol name (e.g., "SBE_PROTOCOL", "JSON_PROTOCOL", "WEB_SOCKET_PROTOCOL")
     */
    String getProtocolName();

    /**
     * Applies this protocol to a player session by configuring the Netty ChannelPipeline.
     * <p>
     * This method installs protocol-specific handlers into the session's channel pipeline,
     * including frame decoders/encoders, message codecs, and event handlers. The method
     * assumes that any previous handlers have already been cleared from the pipeline if needed.
     * </p>
     * <p>
     * <b>Implementation Note:</b><br>
     * Implementations should add handlers in the correct order to ensure proper message flow.
     * Inbound handlers are added from first-to-last, while outbound handlers are processed
     * in reverse order (last-added executes first).
     * </p>
     *
     * @param playerSession the player session to configure with this protocol
     * @see io.netty.channel.ChannelPipeline
     */
    void applyProtocol(PlayerSession playerSession);

    /**
     * Applies this protocol to a player session with optional pipeline clearing.
     * <p>
     * This method provides control over whether existing handlers should be removed
     * before applying the new protocol. This is useful when switching protocols
     * mid-session (e.g., upgrading from HTTP to WebSocket, or switching from login
     * protocol to game protocol).
     * </p>
     *
     * @param playerSession                  the player session to configure with this protocol
     * @param clearExistingProtocolHandlers  if true, removes all existing handlers from the pipeline
     *                                       before applying this protocol; if false, adds handlers
     *                                       to the existing pipeline
     */
    void applyProtocol(PlayerSession playerSession, boolean clearExistingProtocolHandlers);
}
