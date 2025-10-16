package com.akakata.protocols.impl;

import com.akakata.app.PlayerSession;
import com.akakata.handlers.codec.NulEncoder;
import com.akakata.protocols.AbstractNettyProtocol;
import com.akakata.util.NettyUtils;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

/**
 * Text-based protocol using null-terminated string messages.
 * <p>
 * This protocol provides simple string-based communication using null character (\\0)
 * as message delimiter. It's ideal for text-based protocols, telnet-like interfaces,
 * and integration with legacy systems that use line-based communication.
 * </p>
 * <p>
 * <b>Protocol Characteristics:</b>
 * <ul>
 *   <li>Encoding: UTF-8 text strings</li>
 *   <li>Framing: Null delimiter (\\0)</li>
 *   <li>Message Size: Configurable max frame size (default must be set)</li>
 *   <li>Human Readable: Yes (plain text)</li>
 *   <li>Use Case: Admin consoles, debugging, legacy integrations</li>
 * </ul>
 * </p>
 * <p>
 * <b>Message Format:</b>
 * <pre>
 * +----------------+-----+
 * | Text Message   | \\0  |
 * | UTF-8 string   | 1 B |
 * +----------------+-----+
 *
 * Example:
 * "LOGIN player1 password123\\0"
 * "MOVE 100 200\\0"
 * </pre>
 * </p>
 * <p>
 * <b>Pipeline Configuration:</b>
 * <pre>
 * Inbound:  framer → stringDecoder
 * Outbound: nulEncoder → stringEncoder
 * </pre>
 * </p>
 * <p>
 * <b>Important Notes:</b>
 * <ul>
 *   <li>Messages must not contain null characters except as delimiter</li>
 *   <li>Frame size limits maximum message length (prevents memory exhaustion)</li>
 *   <li>No length prefix - messages are delimited by null character only</li>
 *   <li>Uses Netty's built-in StringDecoder/StringEncoder for UTF-8 handling</li>
 * </ul>
 * </p>
 * <p>
 * <b>Note:</b> This implementation uses setter injection (legacy pattern).
 * Consider refactoring to constructor injection for consistency with other protocols.
 * </p>
 *
 * @author Kyia
 * @see StringDecoder
 * @see StringEncoder
 * @see DelimiterBasedFrameDecoder
 */
public class StringProtocol extends AbstractNettyProtocol {

    /**
     * Maximum frame size in bytes. Messages exceeding this size will be rejected.
     */
    private int frameSize;

    /**
     * Custom encoder that appends null terminator to outgoing string messages.
     */
    private NulEncoder nulEncoder;

    /**
     * Netty's built-in decoder that converts ByteBuf to String using UTF-8 encoding.
     */
    private StringDecoder stringDecoder;

    /**
     * Netty's built-in encoder that converts String to ByteBuf using UTF-8 encoding.
     */
    private StringEncoder stringEncoder;

    /**
     * Constructs a new String protocol with default configuration.
     * <p>
     * Dependencies must be set via setter methods after construction.
     * </p>
     */
    public StringProtocol() {
        super("STRING_PROTOCOL");
    }

    /**
     * Constructs a new String protocol with full configuration.
     *
     * @param frameSize      maximum frame size in bytes (prevents memory exhaustion attacks)
     * @param nulEncoder     encoder that appends null terminator to outgoing messages
     * @param stringDecoder  decoder that converts ByteBuf to UTF-8 String
     * @param stringEncoder  encoder that converts String to UTF-8 ByteBuf
     */
    public StringProtocol(int frameSize, NulEncoder nulEncoder,
                          StringDecoder stringDecoder, StringEncoder stringEncoder) {
        super("STRING_PROTOCOL");
        this.frameSize = frameSize;
        this.nulEncoder = nulEncoder;
        this.stringDecoder = stringDecoder;
        this.stringEncoder = stringEncoder;
    }

    /**
     * Configures the channel pipeline with String protocol handlers.
     * <p>
     * Adds handlers in the following order:
     * <ol>
     *   <li><b>framer</b> - DelimiterBasedFrameDecoder using null delimiter (inbound)</li>
     *   <li><b>stringDecoder</b> - Converts ByteBuf to UTF-8 String (inbound)</li>
     *   <li><b>nulEncoder</b> - Appends null terminator to messages (outbound)</li>
     *   <li><b>stringEncoder</b> - Converts String to UTF-8 ByteBuf (outbound)</li>
     * </ol>
     * </p>
     * <p>
     * <b>Note:</b> This protocol does NOT add an event handler automatically.
     * The caller is responsible for adding business logic handlers after applying this protocol.
     * </p>
     *
     * @param playerSession the session to configure with String protocol
     */
    @Override
    public void applyProtocol(PlayerSession playerSession) {
        ChannelPipeline pipeline = NettyUtils.getPipeLineOfConnection(playerSession);

        // Upstream handlers or encoders (i.e towards server) are added to
        // pipeline now.
        pipeline.addLast("framer", new DelimiterBasedFrameDecoder(frameSize, Delimiters.nulDelimiter()));
        pipeline.addLast("stringDecoder", stringDecoder);

        // Downstream handlers (i.e towards client) are added to pipeline now.
        pipeline.addLast("nulEncoder", nulEncoder);
        pipeline.addLast("stringEncoder", stringEncoder);
    }

    public int getFrameSize() {
        return frameSize;
    }

    public void setFrameSize(int frameSize) {
        this.frameSize = frameSize;
    }

    public NulEncoder getNulEncoder() {
        return nulEncoder;
    }

    public void setNulEncoder(NulEncoder nulEncoder) {
        this.nulEncoder = nulEncoder;
    }

    public StringDecoder getStringDecoder() {
        return stringDecoder;
    }

    public void setStringDecoder(StringDecoder stringDecoder) {
        this.stringDecoder = stringDecoder;
    }

    public StringEncoder getStringEncoder() {
        return stringEncoder;
    }

    public void setStringEncoder(StringEncoder stringEncoder) {
        this.stringEncoder = stringEncoder;
    }
}
