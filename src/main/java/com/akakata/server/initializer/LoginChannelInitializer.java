package com.akakata.server.initializer;

import com.akakata.handlers.LoginHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * @author Kyia
 */
public class LoginChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final int frameSize;
    private final ChannelHandler eventDecoder;
    private final LoginHandler loginHandler;
    private final LengthFieldPrepender lengthFieldPrepender;

    @Inject
    public LoginChannelInitializer(@Named("tcpDecoder") ChannelHandler eventDecoder,
                                   LoginHandler loginHandler,
                                   LengthFieldPrepender lengthFieldPrepender) {
        this(1024, eventDecoder, loginHandler, lengthFieldPrepender);
    }

    public LoginChannelInitializer(int frameSize,
                                   ChannelHandler eventDecoder,
                                   LoginHandler loginHandler,
                                   LengthFieldPrepender lengthFieldPrepender) {
        this.frameSize = frameSize;
        this.eventDecoder = eventDecoder;
        this.loginHandler = loginHandler;
        this.lengthFieldPrepender = lengthFieldPrepender;
    }

    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        // Create a default pipeline implementation.
        ChannelPipeline pipeline = ch.pipeline();

        pipeline.addLast(createLengthBasedFrameDecoder());
        pipeline.addLast(eventDecoder);
        pipeline.addLast(loginHandler);
        pipeline.addLast(lengthFieldPrepender);
    }

    private ChannelHandler createLengthBasedFrameDecoder() {
        return new LengthFieldBasedFrameDecoder(frameSize, 0, 2, 0, 2);
    }
}
