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
 * @author Kelvin
 */
public class NettyTCPServer extends AbstractNettyServer {

    private static final Logger LOG = LoggerFactory.getLogger(NettyTCPServer.class);

    private ServerBootstrap serverBootstrap;

    public NettyTCPServer(NettyConfig nettyConfig, ChannelInitializer<? extends Channel> channelInitializer) {
        super(nettyConfig, channelInitializer);
    }

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
        } catch (Exception e) {
            LOG.error("TCP Server start error {}, going to shut down", e.getMessage(), e);
            super.stopServer();
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private void applyChannelOptions(Map<ChannelOption<?>, Object> options) {
        options.forEach((option, value) -> serverBootstrap.option((ChannelOption) option, value));
    }

    @SuppressWarnings("unchecked")
    private void applyChannelChildOptions(Map<ChannelOption<?>, Object> options) {
        options.forEach((option, value) -> serverBootstrap.childOption((ChannelOption) option, value));
    }

    @Override
    public void setChannelInitializer(ChannelInitializer<? extends Channel> initializer) {
        this.channelInitializer = initializer;
        serverBootstrap.childHandler(initializer);
    }

    @Override
    public String toString() {
        return "NettyTCPServer [socketAddress=" + nettyConfig.getSocketAddress()
                + ", portNumber=" + nettyConfig.getPortNumber() + "]";
    }
}
