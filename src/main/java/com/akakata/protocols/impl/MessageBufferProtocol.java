package com.akakata.protocols.impl;

import com.akakata.app.PlayerSession;
import com.akakata.handlers.DefaultToServerHandler;
import com.akakata.handlers.codec.MessageBufferEventDecoder;
import com.akakata.handlers.codec.MessageBufferEventEncoder;
import com.akakata.protocols.AbstractNettyProtocol;
import com.akakata.util.NettyUtils;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldPrepender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom binary protocol using MessageBuffer abstraction for flexible message encoding.
 * <p>
 * This protocol provides a balance between performance and flexibility, using a custom
 * {@code MessageBuffer} abstraction that wraps Netty's ByteBuf. It's suitable for scenarios
 * where you need more control over message structure than JSON provides, but don't need
 * the extreme performance of SBE.
 * </p>
 * <p>
 * <b>Protocol Characteristics:</b>
 * <ul>
 *   <li>Encoding: Custom binary (MessageBuffer-based)</li>
 *   <li>Framing: 2-byte length prefix</li>
 *   <li>Performance: Faster than JSON, slower than SBE</li>
 *   <li>Flexibility: High (dynamic message structure)</li>
 *   <li>Schema: Runtime-defined (no compile-time validation)</li>
 * </ul>
 * </p>
 * <p>
 * <b>Message Format:</b>
 * <pre>
 * +--------+--------------------+
 * | Length | MessageBuffer Body |
 * | 2 bytes| Variable length    |
 * +--------+--------------------+
 *
 * MessageBuffer typically contains:
 * - Event type (int)
 * - Event timestamp (long)
 * - Custom fields (primitives, strings, byte arrays)
 * </pre>
 * </p>
 * <p>
 * <b>Pipeline Configuration:</b>
 * <pre>
 * Inbound:  lengthDecoder → messageBufferEventDecoder → eventHandler
 * Outbound: eventHandler → messageBufferEventEncoder → lengthFieldPrepender
 * </pre>
 * </p>
 * <p>
 * <b>Use Cases:</b>
 * <ul>
 *   <li>Custom game protocols with dynamic message structure</li>
 *   <li>Legacy protocol compatibility</li>
 *   <li>Prototyping before committing to SBE schema</li>
 * </ul>
 * </p>
 * <p>
 * <b>Note:</b> This implementation uses setter injection (legacy pattern).
 * Consider refactoring to constructor injection for consistency with other protocols.
 * </p>
 *
 * @author Kelvin
 * @see MessageBufferEventDecoder
 * @see MessageBufferEventEncoder
 * @see com.akakata.communication.MessageBuffer
 */
public class MessageBufferProtocol extends AbstractNettyProtocol {

    private static final Logger LOG = LoggerFactory.getLogger(MessageBufferProtocol.class);

    private MessageBufferEventDecoder messageBufferEventDecoder;
    private MessageBufferEventEncoder messageBufferEventEncoder;
    /**
     * Netty handler that prepends the 2-byte length header to outgoing messages.
     * The length field contains the size of the message body (excluding the length field itself).
     */
    private LengthFieldPrepender lengthFieldPrepender;

    /**
     * Constructs a new MessageBuffer protocol with default configuration.
     * <p>
     * Dependencies must be set via setter methods after construction.
     * </p>
     */
    public MessageBufferProtocol() {
        super("MESSAGE_BUFFER_PROTOCOL");
    }

    /**
     * Configures the channel pipeline with MessageBuffer protocol handlers.
     * <p>
     * Adds handlers in the following order:
     * <ol>
     *   <li><b>lengthDecoder</b> - Extracts frames based on 2-byte length prefix (inbound)</li>
     *   <li><b>messageBufferEventDecoder</b> - Decodes binary to Event objects (inbound)</li>
     *   <li><b>eventHandler</b> - Processes decoded events (bidirectional)</li>
     *   <li><b>lengthFieldPrepender</b> - Adds 2-byte length prefix (outbound)</li>
     *   <li><b>messageBufferEventEncoder</b> - Encodes Event objects to binary (outbound)</li>
     * </ol>
     * </p>
     * <p>
     * <b>Note:</b> Outbound handlers execute in reverse order of addition, so
     * messageBufferEventEncoder runs before lengthFieldPrepender.
     * </p>
     *
     * @param playerSession the session to configure with MessageBuffer protocol
     */
    @Override
    public void applyProtocol(PlayerSession playerSession) {
        LOG.debug("Going to apply protocol on session: {}.", playerSession);

        ChannelPipeline pipeline = NettyUtils.getPipeLineOfConnection(playerSession);

        // Upstream handlers or encoders (i.e towards server) are added to pipeline now.
        pipeline.addLast("lengthDecoder", createLengthBasedFrameDecoder());
        pipeline.addLast("messageBufferEventDecoder", messageBufferEventDecoder);
        pipeline.addLast("eventHandler", new DefaultToServerHandler(playerSession));

        // Downstream handlers - Filter for data which flows from server to client.
        // Note that the last handler added is actually the first handler for outgoing data.
        pipeline.addLast("lengthFieldPrepender", lengthFieldPrepender);
        pipeline.addLast("messageBufferEventEncoder", messageBufferEventEncoder);
    }

    public MessageBufferEventDecoder getMessageBufferEventDecoder() {
        return messageBufferEventDecoder;
    }

    public void setMessageBufferEventDecoder(MessageBufferEventDecoder messageBufferEventDecoder) {
        this.messageBufferEventDecoder = messageBufferEventDecoder;
    }

    public MessageBufferEventEncoder getMessageBufferEventEncoder() {
        return messageBufferEventEncoder;
    }

    public void setMessageBufferEventEncoder(MessageBufferEventEncoder messageBufferEventEncoder) {
        this.messageBufferEventEncoder = messageBufferEventEncoder;
    }

    public LengthFieldPrepender getLengthFieldPrepender() {
        return lengthFieldPrepender;
    }

    public void setLengthFieldPrepender(LengthFieldPrepender lengthFieldPrepender) {
        this.lengthFieldPrepender = lengthFieldPrepender;
    }
}
