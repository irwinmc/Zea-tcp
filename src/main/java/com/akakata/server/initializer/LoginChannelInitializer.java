package com.akakata.server.initializer;

import com.akakata.handlers.LoginHandler;
import com.akakata.handlers.TransportLifecycleHandler;
import com.akakata.server.ConnectionAdmission;
import com.akakata.server.TransportLimits;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Installs length-based TCP framing, the login decoder and the login handler.
 *
 * @author Kyia
 */
public class LoginChannelInitializer extends ChannelInitializer<SocketChannel> {

    /**
     * Maximum inbound frame size in bytes.
     */
    private final int frameSize;

    /**
     * Socket admission and lifetime policy shared with the WebSocket listener.
     */
    private final ConnectionAdmission admission;

    /**
     * Decoder selected for inbound login frames.
     */
    private final ChannelHandler eventDecoder;

    /**
     * Handler that authenticates TCP login events.
     */
    private final LoginHandler loginHandler;

    /**
     * Shared outbound TCP frame-length encoder.
     */
    private final LengthFieldPrepender lengthFieldPrepender;

    /**
     * Creates a login channel initializer instance with the supplied dependencies.
     *
     * @param eventDecoder         the event decoder
     * @param loginHandler         the login handler
     * @param lengthFieldPrepender the length field prepender
     */
    @Inject
    public LoginChannelInitializer(@Named("tcpDecoder") ChannelHandler eventDecoder,
                                   LoginHandler loginHandler,
                                   LengthFieldPrepender lengthFieldPrepender) {
        this(1024, eventDecoder, loginHandler, lengthFieldPrepender);
    }

    /**
     * Creates a login channel initializer instance with the supplied dependencies.
     *
     * @param frameSize            the maximum accepted frame size in bytes
     * @param eventDecoder         the event decoder
     * @param loginHandler         the login handler
     * @param lengthFieldPrepender the length field prepender
     */
    public LoginChannelInitializer(int frameSize,
                                   ChannelHandler eventDecoder,
                                   LoginHandler loginHandler,
                                   LengthFieldPrepender lengthFieldPrepender) {
        this(frameSize, eventDecoder, loginHandler, lengthFieldPrepender,
                new ConnectionAdmission(TransportLimits.DEFAULT));
    }

    /**
     * Creates TCP login framing with a shared socket lifetime policy.
     *
     * @param frameSize            maximum TCP frame bytes including its two-byte prefix
     * @param eventDecoder         the shared login decoder
     * @param loginHandler         the authentication handler
     * @param lengthFieldPrepender the shared outbound framing encoder
     * @param admission            the shared TCP/WebSocket socket budget
     */
    public LoginChannelInitializer(int frameSize, ChannelHandler eventDecoder, LoginHandler loginHandler,
                                   LengthFieldPrepender lengthFieldPrepender, ConnectionAdmission admission) {
        this.admission = admission;
        this.frameSize = frameSize;
        this.eventDecoder = eventDecoder;
        this.loginHandler = loginHandler;
        this.lengthFieldPrepender = lengthFieldPrepender;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        // Create a default pipeline implementation.
        ChannelPipeline pipeline = ch.pipeline();

        TransportLifecycleHandler.install(pipeline, admission);
        pipeline.addLast(createLengthBasedFrameDecoder());
        pipeline.addLast(eventDecoder);
        pipeline.addLast(loginHandler);
        pipeline.addLast(lengthFieldPrepender);
    }

    /**
     * Creates an inbound decoder for a two-byte length prefix, stripping that prefix from decoded
     * frames.
     *
     * @return a new frame decoder
     */
    private ChannelHandler createLengthBasedFrameDecoder() {
        return new LengthFieldBasedFrameDecoder(frameSize, 0, 2, 0, 2);
    }
}
