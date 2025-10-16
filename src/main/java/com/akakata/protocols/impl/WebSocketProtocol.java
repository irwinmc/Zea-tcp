package com.akakata.protocols.impl;

import com.akakata.app.PlayerSession;
import com.akakata.context.AppContext;
import com.akakata.handlers.DefaultToServerHandler;
import com.akakata.handlers.codec.WebSocketEventDecoder;
import com.akakata.handlers.codec.WebSocketEventEncoder;
import com.akakata.protocols.AbstractNettyProtocol;
import com.akakata.util.NettyUtils;
import io.netty.channel.ChannelPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

/**
 * WebSocket protocol implementation for browser-based clients.
 * <p>
 * This protocol handles WebSocket connections, providing full-duplex communication
 * channels over a single TCP connection. WebSocket is ideal for browser-based games
 * and web applications that require real-time bidirectional communication.
 * </p>
 * <p>
 * <b>Protocol Characteristics:</b>
 * <ul>
 *   <li>Transport: WebSocket (RFC 6455)</li>
 *   <li>Framing: WebSocket frames (handled by Netty's WebSocketServerProtocolHandler)</li>
 *   <li>Message Types: Binary or Text frames</li>
 *   <li>Browser Support: All modern browsers</li>
 *   <li>Connection Upgrade: HTTP → WebSocket handshake</li>
 * </ul>
 * </p>
 * <p>
 * <b>Connection Lifecycle:</b>
 * <pre>
 * 1. Client sends HTTP Upgrade request to /ws
 * 2. Server responds with 101 Switching Protocols
 * 3. WebSocketLoginHandler authenticates the session
 * 4. After authentication, this protocol is applied
 * 5. WebSocketLoginHandler is removed from pipeline
 * 6. Game-specific event handlers are installed
 * </pre>
 * </p>
 * <p>
 * <b>Pipeline Configuration:</b>
 * <pre>
 * Before authentication:
 *   httpCodec → httpAggregator → webSocketHandler → webSocketLoginHandler
 *
 * After applyProtocol():
 *   httpCodec → httpAggregator → webSocketHandler → webSocketEventDecoder
 *   → eventHandler → webSocketEventEncoder
 * </pre>
 * </p>
 * <p>
 * <b>Important Note:</b><br>
 * This protocol does NOT use length-based frame decoding because WebSocket framing
 * is already handled by Netty's WebSocketServerProtocolHandler. The decoder/encoder
 * only need to convert between WebSocket frames and Event objects.
 * </p>
 *
 * @author Kelvin
 * @see WebSocketEventDecoder
 * @see WebSocketEventEncoder
 * @see io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
 */
public class WebSocketProtocol extends AbstractNettyProtocol {

    private static final Logger LOG = LoggerFactory.getLogger(WebSocketProtocol.class);

    private final WebSocketEventDecoder webSocketEventDecoder;
    private final WebSocketEventEncoder webSocketEventEncoder;

    /**
     * Constructs a new WebSocket protocol with required codec components.
     * <p>
     * All dependencies are injected via Dagger 2 and configured in {@code ProtocolModule}.
     * </p>
     *
     * @param webSocketEventDecoder decodes WebSocket frames to Event objects
     * @param webSocketEventEncoder encodes Event objects to WebSocket frames
     */
    @Inject
    public WebSocketProtocol(WebSocketEventDecoder webSocketEventDecoder,
                            WebSocketEventEncoder webSocketEventEncoder) {
        super("WEB_SOCKET_PROTOCOL");
        this.webSocketEventDecoder = webSocketEventDecoder;
        this.webSocketEventEncoder = webSocketEventEncoder;
    }

    /**
     * Configures the channel pipeline with WebSocket protocol handlers.
     * <p>
     * This method is typically called after successful authentication by the
     * {@code WebSocketLoginHandler}. It removes the login handler and installs
     * the game-specific event processing handlers.
     * </p>
     * <p>
     * <b>Handler Order:</b>
     * <ol>
     *   <li><b>webSocketEventDecoder</b> - Decodes WebSocket frames to Event objects</li>
     *   <li><b>eventHandler</b> - Processes decoded events (business logic)</li>
     *   <li><b>webSocketEventEncoder</b> - Encodes Event objects to WebSocket frames</li>
     * </ol>
     * </p>
     * <p>
     * <b>Note:</b> This method removes the {@code WEB_SOCKET_LOGIN_HANDLER} from the
     * pipeline before adding game handlers. This assumes authentication has completed.
     * </p>
     *
     * @param playerSession the authenticated session to configure with WebSocket protocol
     */
    @Override
    public void applyProtocol(PlayerSession playerSession) {
        LOG.trace("Going to apply {} on session: {}", getProtocolName(), playerSession);

        ChannelPipeline pipeline = NettyUtils.getPipeLineOfConnection(playerSession);

        // Remove login handler
        pipeline.remove(AppContext.WEB_SOCKET_LOGIN_HANDLER);

        // Add more handler
        pipeline.addLast("webSocketEventDecoder", webSocketEventDecoder);
        pipeline.addLast("eventHandler", new DefaultToServerHandler(playerSession));
        pipeline.addLast("webSocketEventEncoder", webSocketEventEncoder);
    }
}
