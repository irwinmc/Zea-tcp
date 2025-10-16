package com.akakata.protocols.impl;

import com.akakata.app.PlayerSession;
import com.akakata.handlers.DefaultToServerHandler;
import com.akakata.handlers.codec.SbeEventDecoder;
import com.akakata.handlers.codec.SbeEventEncoder;
import com.akakata.protocols.AbstractNettyProtocol;
import com.akakata.util.NettyUtils;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldPrepender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

/**
 * Simple Binary Encoding (SBE) protocol implementation for high-performance binary messaging.
 * <p>
 * This is the <b>default TCP protocol</b> for the game server, providing optimal performance
 * through compact binary encoding and zero-copy message processing. SBE messages use a fixed
 * message schema compiled at build time, eliminating runtime reflection overhead.
 * </p>
 * <p>
 * <b>Protocol Characteristics:</b>
 * <ul>
 *   <li>Encoding: Binary (SBE format)</li>
 *   <li>Framing: 2-byte length prefix</li>
 *   <li>Performance: ~10-50x faster than JSON</li>
 *   <li>Message Size: 40-80% smaller than JSON</li>
 *   <li>Type Safety: Compile-time schema validation</li>
 * </ul>
 * </p>
 * <p>
 * <b>Message Format:</b>
 * <pre>
 * +--------+------------------+
 * | Length | SBE Message Body |
 * | 2 bytes| Variable length  |
 * +--------+------------------+
 *
 * SBE Message Body:
 * - Fixed header (templateId, schemaId, version)
 * - Fixed-size fields
 * - Variable-length fields (strings, byte arrays)
 * </pre>
 * </p>
 * <p>
 * <b>Pipeline Configuration:</b>
 * <pre>
 * Inbound:  lengthDecoder → sbeDecoder → eventHandler
 * Outbound: eventHandler → sbeEncoder → lengthFieldPrepender
 * </pre>
 * </p>
 * <p>
 * <b>Usage:</b><br>
 * This protocol is automatically applied via dependency injection. All dependencies
 * (decoder, encoder, prepender) are injected through the constructor.
 * </p>
 *
 * @author Kelvin
 * @see SbeEventDecoder
 * @see SbeEventEncoder
 */
public class SbeProtocol extends AbstractNettyProtocol {

    private static final Logger LOG = LoggerFactory.getLogger(SbeProtocol.class);

    private final SbeEventDecoder sbeEventDecoder;
    private final SbeEventEncoder sbeEventEncoder;
    private final LengthFieldPrepender lengthFieldPrepender;

    /**
     * Constructs a new SBE protocol with required codec components.
     * <p>
     * All dependencies are injected via Dagger 2 and configured in {@code ProtocolModule}.
     * </p>
     *
     * @param sbeEventDecoder       decodes incoming SBE binary messages to Event objects
     * @param sbeEventEncoder       encodes outgoing Event objects to SBE binary format
     * @param lengthFieldPrepender  prepends 2-byte length header to outgoing messages
     */
    @Inject
    public SbeProtocol(SbeEventDecoder sbeEventDecoder,
                      SbeEventEncoder sbeEventEncoder,
                      LengthFieldPrepender lengthFieldPrepender) {
        super("SBE_PROTOCOL");
        this.sbeEventDecoder = sbeEventDecoder;
        this.sbeEventEncoder = sbeEventEncoder;
        this.lengthFieldPrepender = lengthFieldPrepender;
    }

    /**
     * Configures the channel pipeline with SBE protocol handlers.
     * <p>
     * Adds handlers in the following order:
     * <ol>
     *   <li><b>lengthDecoder</b> - Extracts frames based on 2-byte length prefix</li>
     *   <li><b>sbeDecoder</b> - Decodes SBE binary to Event objects</li>
     *   <li><b>eventHandler</b> - Processes decoded events (business logic)</li>
     *   <li><b>lengthFieldPrepender</b> - Adds 2-byte length prefix to outgoing messages</li>
     *   <li><b>sbeEncoder</b> - Encodes Event objects to SBE binary format</li>
     * </ol>
     * </p>
     *
     * @param playerSession the session to configure with SBE protocol
     */
    @Override
    public void applyProtocol(PlayerSession playerSession) {
        LOG.debug("Applying SBE protocol on session {}", playerSession);

        ChannelPipeline pipeline = NettyUtils.getPipeLineOfConnection(playerSession);
        pipeline.addLast("lengthDecoder", createLengthBasedFrameDecoder());
        pipeline.addLast("sbeDecoder", sbeEventDecoder);
        pipeline.addLast("eventHandler", new DefaultToServerHandler(playerSession));
        pipeline.addLast("lengthFieldPrepender", lengthFieldPrepender);
        pipeline.addLast("sbeEncoder", sbeEventEncoder);
    }
}
