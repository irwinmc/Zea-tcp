package com.akakata.server.initializer;

import com.akakata.handlers.LoginHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;

/**
 * @author Kyia
 */
public class LoginChannelInitializer extends ChannelInitializer<SocketChannel> {

    private int frameSize = 1024;
    private ChannelHandler eventDecoder;
    private LoginHandler loginHandler;
    private LengthFieldPrepender lengthFieldPrepender;

    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        // Create a default pipeline implementation.
        ChannelPipeline pipeline = ch.pipeline();

        pipeline.addLast(createLengthBasedFrameDecoder());
        pipeline.addLast(eventDecoder);
        pipeline.addLast(loginHandler);
        pipeline.addLast(lengthFieldPrepender);
    }

    public ChannelHandler createLengthBasedFrameDecoder() {
        return new LengthFieldBasedFrameDecoder(frameSize, 0, 2, 0, 2);
    }

    public int getFrameSize() {
        return frameSize;
    }

    public void setFrameSize(int frameSize) {
        this.frameSize = frameSize;
    }

    public ChannelHandler getEventDecoder() {
        return eventDecoder;
    }

    public void setEventDecoder(ChannelHandler eventDecoder) {
        this.eventDecoder = eventDecoder;
    }

    public LoginHandler getLoginHandler() {
        return loginHandler;
    }

    public void setLoginHandler(LoginHandler loginHandler) {
        this.loginHandler = loginHandler;
    }

    public LengthFieldPrepender getLengthFieldPrepender() {
        return lengthFieldPrepender;
    }

    public void setLengthFieldPrepender(LengthFieldPrepender lengthFieldPrepender) {
        this.lengthFieldPrepender = lengthFieldPrepender;
    }
}
