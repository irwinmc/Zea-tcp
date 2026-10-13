package com.akakata.server.impl;

import com.akakata.server.NettyConfig;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Binds NIO server sockets with the configured initializer and channel options.
 *
 * @author Kelvin
 */
public class NettyTCPServer extends AbstractNettyServer {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(NettyTCPServer.class);

    /**
     * Bootstrap used to bind the listening channel and initialize accepted channels.
     */
    private ServerBootstrap serverBootstrap;

    /**
     * Actual listening channel, safely published for nonblocking availability checks.
     */
    private volatile Channel listeningChannel;

    /**
     * Creates a netty tcp server instance with the supplied dependencies.
     *
     * @param nettyConfig        the server bind and channel settings
     * @param channelInitializer the initializer for accepted channels
     */
    public NettyTCPServer(NettyConfig nettyConfig, ChannelInitializer<? extends Channel> channelInitializer) {
        super(nettyConfig, channelInitializer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startServer() throws Exception {
        try {
            serverBootstrap = new ServerBootstrap();

            Map<ChannelOption<?>, Object> channelOptions = nettyConfig.getChannelOptions();
            if (channelOptions != null) {
                applyChannelOptions(channelOptions);
            }

            Map<ChannelOption<?>, Object> channelChildOptions = nettyConfig.getChannelChildOptions();
            if (channelChildOptions != null) {
                applyChannelChildOptions(channelChildOptions);
            }

            serverBootstrap.group(getBossGroup(), getWorkerGroup())
                    .channel(NioServerSocketChannel.class)
                    .childHandler(getChannelInitializer());

            Channel serverChannel = serverBootstrap.bind(nettyConfig.getSocketAddress()).sync()
                    .channel();

            ALL_CHANNELS.add(serverChannel);
            listeningChannel = serverChannel;
        } catch (Exception e) {
            LOG.error("TCP Server start error {}, going to shut down", e.getMessage(), e);
            super.stopServer();
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isListening() {
        Channel channel = listeningChannel;
        return channel != null && channel.isActive();
    }

    /**
     * Applies configured options to the server bootstrap's listening channel.
     *
     * @param options the channel options to apply
     */
    @SuppressWarnings("unchecked")
    private void applyChannelOptions(Map<ChannelOption<?>, Object> options) {
        options.forEach((option, value) -> serverBootstrap.option((ChannelOption) option, value));
    }

    /**
     * Applies configured options to accepted child channels.
     *
     * @param options the channel options to apply
     */
    @SuppressWarnings("unchecked")
    private void applyChannelChildOptions(Map<ChannelOption<?>, Object> options) {
        options.forEach((option, value) -> serverBootstrap.childOption((ChannelOption) option, value));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setChannelInitializer(ChannelInitializer<? extends Channel> initializer) {
        this.channelInitializer = initializer;
        serverBootstrap.childHandler(initializer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "NettyTCPServer [socketAddress=" + nettyConfig.getSocketAddress()
                + ", portNumber=" + nettyConfig.getPortNumber() + "]";
    }
}
