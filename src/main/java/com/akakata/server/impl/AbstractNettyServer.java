package com.akakata.server.impl;

import com.akakata.server.NettyConfig;
import com.akakata.server.NettyServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * @author Kelvin
 */
public abstract class AbstractNettyServer implements NettyServer {

    public static final ChannelGroup ALL_CHANNELS = new DefaultChannelGroup("ZEALSERVER-CHANNELS", GlobalEventExecutor.INSTANCE);
    private static final Logger LOG = LoggerFactory.getLogger(AbstractNettyServer.class);
    protected final NettyConfig nettyConfig;
    protected ChannelInitializer<? extends Channel> channelInitializer;

    public AbstractNettyServer(NettyConfig nettyConfig, ChannelInitializer<? extends Channel> channelInitializer) {
        this.nettyConfig = nettyConfig;
        this.channelInitializer = channelInitializer;
    }

    @Override
    public void startServer(int port) throws Exception {
        nettyConfig.setPortNumber(port);
        nettyConfig.setSocketAddress(new InetSocketAddress(port));
        startServer();
    }

    @Override
    public void startServer(InetSocketAddress socketAddress) throws Exception {
        nettyConfig.setSocketAddress(socketAddress);
        startServer();
    }

    @Override
    public void stopServer() throws Exception {
        LOG.debug("In stopServer method of class: {}", this.getClass().getName());

        ChannelGroupFuture future = ALL_CHANNELS.close();
        try {
            future.await();
        } catch (InterruptedException e) {
            LOG.error("Exception occurred while waiting for channels to close: {}", e);
            Thread.currentThread().interrupt();
        } finally {
            // EventLoopGroup 生命周期由 ServerContext / NetworkBootstrap 统一管理，此处不再关闭。
        }
    }

    @Override
    public ChannelInitializer<? extends Channel> getChannelInitializer() {
        return channelInitializer;
    }

    @Override
    public NettyConfig getNettyConfig() {
        return nettyConfig;
    }

    protected EventLoopGroup getBossGroup() {
        return nettyConfig.getBossGroup();
    }

    protected EventLoopGroup getWorkerGroup() {
        return nettyConfig.getWorkerGroup();
    }

    @Override
    public InetSocketAddress getSocketAddress() {
        return nettyConfig.getSocketAddress();
    }

    @Override
    public String toString() {
        return "NettyServer [socketAddress=" + nettyConfig.getSocketAddress() + ", portNumber=" + nettyConfig.getPortNumber() + "]";
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj);
    }
}
