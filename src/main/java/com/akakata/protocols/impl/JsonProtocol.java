package com.akakata.protocols.impl;

import com.akakata.app.PlayerSession;
import com.akakata.handlers.DefaultToServerHandler;
import com.akakata.handlers.codec.JsonDecoder;
import com.akakata.handlers.codec.JsonEncoder;
import com.akakata.protocols.AbstractNettyProtocol;
import com.akakata.util.NettyUtils;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldPrepender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

/**
 * JSON-based protocol implementation for human-readable messaging.
 * <p>
 * This protocol uses JSON encoding for messages, providing excellent cross-platform
 * compatibility and ease of debugging. While slower than binary protocols (SBE),
 * JSON is ideal for development, debugging tools, and integration with web-based clients.
 * </p>
 * <p>
 * <b>Protocol Characteristics:</b>
 * <ul>
 *   <li>Encoding: JSON (UTF-8 text)</li>
 *   <li>Framing: 2-byte length prefix</li>
 *   <li>Performance: 10-50x slower than SBE</li>
 *   <li>Message Size: 2-3x larger than SBE</li>
 *   <li>Human Readable: Yes (excellent for debugging)</li>
 *   <li>Cross-platform: Excellent (any language with JSON support)</li>
 * </ul>
 * </p>
 * <p>
 * <b>Message Format:</b>
 * <pre>
 * +--------+-------------------+
 * | Length | JSON Message Body |
 * | 2 bytes| UTF-8 text        |
 * +--------+-------------------+
 *
 * Example JSON payload:
 * {
 *   "type": "LOGIN",
 *   "username": "player1",
 *   "timestamp": 1234567890
 * }
 * </pre>
 * </p>
 * <p>
 * <b>Pipeline Configuration:</b>
 * <pre>
 * Inbound:  lengthDecoder → jsonDecoder → eventHandler
 * Outbound: eventHandler → jsonEncoder → lengthFieldPrepender
 * </pre>
 * </p>
 * <p>
 * <b>Use Cases:</b>
 * <ul>
 *   <li>Development and debugging (message inspection)</li>
 *   <li>Admin tools and monitoring dashboards</li>
 *   <li>Cross-platform clients (web, mobile)</li>
 *   <li>Integration with third-party systems</li>
 * </ul>
 * </p>
 *
 * @author Kelvin
 * @see JsonDecoder
 * @see JsonEncoder
 */
public class JsonProtocol extends AbstractNettyProtocol {

    private static final Logger LOG = LoggerFactory.getLogger(JsonProtocol.class);

    private final JsonDecoder jsonDecoder;
    private final JsonEncoder jsonEncoder;
    private final LengthFieldPrepender lengthFieldPrepender;

    /**
     * Constructs a new JSON protocol with required codec components.
     * <p>
     * All dependencies are injected via Dagger 2 and configured in {@code ProtocolModule}.
     * </p>
     *
     * @param jsonDecoder          decodes incoming JSON messages to Event objects
     * @param jsonEncoder          encodes outgoing Event objects to JSON format
     * @param lengthFieldPrepender prepends 2-byte length header to outgoing messages
     */
    @Inject
    public JsonProtocol(JsonDecoder jsonDecoder,
                       JsonEncoder jsonEncoder,
                       LengthFieldPrepender lengthFieldPrepender) {
        super("JSON_PROTOCOL");
        this.jsonDecoder = jsonDecoder;
        this.jsonEncoder = jsonEncoder;
        this.lengthFieldPrepender = lengthFieldPrepender;
    }

    /**
     * Configures the channel pipeline with JSON protocol handlers.
     * <p>
     * <b>Handler Order:</b>
     * <ol>
     *   <li><b>lengthDecoder</b> - Extracts frames based on 2-byte length prefix (inbound)</li>
     *   <li><b>jsonDecoder</b> - Decodes JSON to Event objects (inbound)</li>
     *   <li><b>eventHandler</b> - Processes decoded events (bidirectional)</li>
     *   <li><b>lengthFieldPrepender</b> - Adds 2-byte length prefix (outbound)</li>
     *   <li><b>jsonEncoder</b> - Encodes Event objects to JSON (outbound)</li>
     * </ol>
     * </p>
     * <p>
     * <b>Note:</b> Outbound handlers are executed in reverse order of addition.
     * Therefore, jsonEncoder runs before lengthFieldPrepender for outgoing messages.
     * </p>
     *
     * @param playerSession the session to configure with JSON protocol
     */
    @Override
    public void applyProtocol(PlayerSession playerSession) {
        LOG.debug("Going to apply protocol on session: {}.", playerSession);

        ChannelPipeline pipeline = NettyUtils.getPipeLineOfConnection(playerSession);

        // Upstream handlers or encoders (i.e towards server) are added to pipeline now.
        pipeline.addLast("lengthDecoder", createLengthBasedFrameDecoder());
        pipeline.addLast("jsonDecoder", jsonDecoder);
        pipeline.addLast("eventHandler", new DefaultToServerHandler(playerSession));

        // Downstream handlers (i.e towards client) are added to pipeline now.
        // NOTE the last encoder in the pipeline is the first encoder to be called.
        pipeline.addLast("lengthFieldPrepender", lengthFieldPrepender);
        pipeline.addLast("jsonEncoder", jsonEncoder);
    }
}
