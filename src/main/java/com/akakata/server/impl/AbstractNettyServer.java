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
 * Stores Netty configuration and closes channels in the shared server channel group on stop.
 *
 * <p>Stopping leaves shared event-loop groups running; their lifecycle belongs to
 * the network bootstrap or another external owner.
 *
 * @author Kelvin
 */
public abstract class AbstractNettyServer implements NettyServer {

    /**
     * Shared channel group containing registered server channels across server instances.
     */
    public static final ChannelGroup ALL_CHANNELS = new DefaultChannelGroup("ZEA-SERVER-CHANNELS", GlobalEventExecutor.INSTANCE);

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AbstractNettyServer.class);

    /**
     * Server bind settings, channel options and shared event-loop references.
     */
    protected final NettyConfig nettyConfig;

    /**
     * Pipeline initializer used for accepted channels.
     */
    protected ChannelInitializer<? extends Channel> channelInitializer;

    /**
     * Creates an abstract netty server instance with the supplied dependencies.
     *
     * @param nettyConfig        the server bind and channel settings
     * @param channelInitializer the initializer for accepted channels
     */
    public AbstractNettyServer(NettyConfig nettyConfig, ChannelInitializer<? extends Channel> channelInitializer) {
        this.nettyConfig = nettyConfig;
        this.channelInitializer = channelInitializer;
    }

    /**
     * Starts on the supplied port while preserving the configured bind host.
     *
     * @param port the override port
     * @throws Exception if listener startup fails
     */
    @Override
    public void startServer(int port) throws Exception {
        nettyConfig.setPortNumber(port);
        nettyConfig.setSocketAddress(new InetSocketAddress(nettyConfig.getSocketAddress().getHostString(), port));
        startServer();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void startServer(InetSocketAddress socketAddress) throws Exception {
        nettyConfig.setSocketAddress(socketAddress);
        startServer();
    }

    /**
     * Closes all channels registered in the process-wide server channel group and waits for
     * completion.
     *
     * <p>Shared event-loop groups remain owned by their network resource manager. An
     * interrupted wait restores the interrupt flag after logging the failure.
     *
     * @throws Exception if the operation fails
     */
    @Override
    public void stopServer() throws Exception {
        LOG.debug("In stopServer method of class: {}", this.getClass().getName());

        ChannelGroupFuture future = ALL_CHANNELS.close();
        try {
            future.await();
        } catch (InterruptedException e) {
            LOG.error("Exception occurred while waiting for channels to close: {}", e.getMessage(), e);
            Thread.currentThread().interrupt();
        } finally {
            // EventLoopGroup 生命周期由 ServerContext / NetworkBootstrap 统一管理，此处不再关闭。
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ChannelInitializer<? extends Channel> getChannelInitializer() {
        return channelInitializer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public NettyConfig getNettyConfig() {
        return nettyConfig;
    }

    /**
     * Returns the boss group.
     *
     * @return the boss group
     */
    protected EventLoopGroup getBossGroup() {
        return nettyConfig.getBossGroup();
    }

    /**
     * Returns the worker group.
     *
     * @return the worker group
     */
    protected EventLoopGroup getWorkerGroup() {
        return nettyConfig.getWorkerGroup();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InetSocketAddress getSocketAddress() {
        return nettyConfig.getSocketAddress();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "NettyServer [socketAddress=" + nettyConfig.getSocketAddress() + ", portNumber=" + nettyConfig.getPortNumber() + "]";
    }
}
