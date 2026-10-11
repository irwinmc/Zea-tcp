package com.akakata.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;

import java.net.InetSocketAddress;
import java.util.Map;

/**
 * Stores bind settings, channel options and shared event-loop references.
 *
 * <p>Missing event-loop groups are created lazily using NIO. Injected groups can be
 * shared by several servers and must be closed by their resource owner. Most
 * configuration setters are intended to be used before starting a server.
 *
 * @author Kyia
 */
public class NettyConfig {

    /**
     * Creates a netty config instance.
     */
    public NettyConfig() {
    }

    /**
     * Pipeline initializer used for accepted channels.
     */
    protected ChannelInitializer<? extends Channel> channelInitializer;

    /**
     * Options applied to listening channels.
     */
    private Map<ChannelOption<?>, Object> channelOptions;

    /**
     * Options applied to accepted child channels.
     */
    private Map<ChannelOption<?>, Object> channelChildOptions;

    /**
     * Shared event-loop group for accepting incoming connections.
     */
    private volatile EventLoopGroup bossGroup;

    /**
     * Shared event-loop group for processing connection I/O.
     */
    private volatile EventLoopGroup workerGroup;

    /**
     * Accepting-thread count used when creating a boss group lazily.
     */
    private int bossThreadCount;

    /**
     * I/O-thread count used when creating a worker group lazily.
     */
    private int workerThreadCount;

    /**
     * Cached or explicitly supplied server bind address.
     */
    private volatile InetSocketAddress socketAddress;

    /**
     * Configured server bind port.
     */
    private int portNumber = 18090;

    /**
     * Returns the listening-channel options.
     *
     * @return the listening-channel options
     */
    public Map<ChannelOption<?>, Object> getChannelOptions() {
        return channelOptions;
    }

    /**
     * Sets the listening-channel options.
     *
     * @param channelOptions the options for listening channels
     */
    public void setChannelOptions(Map<ChannelOption<?>, Object> channelOptions) {
        this.channelOptions = channelOptions;
    }

    /**
     * Returns the accepted-channel options.
     *
     * @return the accepted-channel options
     */
    public Map<ChannelOption<?>, Object> getChannelChildOptions() {
        return channelChildOptions;
    }

    /**
     * Sets the accepted-channel options.
     *
     * @param channelChildOptions the options for accepted channels
     */
    public void setChannelChildOptions(Map<ChannelOption<?>, Object> channelChildOptions) {
        this.channelChildOptions = channelChildOptions;
    }

    /**
     * Returns the supplied boss group or lazily creates a NIO group using the configured thread
     * count.
     *
     * @return the group accepting connections
     */
    public EventLoopGroup getBossGroup() {
        if (bossGroup == null) {
            synchronized (this) {
                if (bossGroup == null) {
                    bossGroup = new MultiThreadIoEventLoopGroup(
                            Math.max(0, bossThreadCount), NioIoHandler.newFactory());
                }
            }
        }
        return bossGroup;
    }

    /**
     * Sets the boss group.
     *
     * @param bossGroup the accepting event-loop group
     */
    public void setBossGroup(EventLoopGroup bossGroup) {
        this.bossGroup = bossGroup;
    }

    /**
     * Returns the supplied worker group or lazily creates a NIO group using the configured thread
     * count.
     *
     * @return the group processing connection I/O
     */
    public EventLoopGroup getWorkerGroup() {
        if (workerGroup == null) {
            synchronized (this) {
                if (workerGroup == null) {
                    workerGroup = new MultiThreadIoEventLoopGroup(
                            Math.max(0, workerThreadCount), NioIoHandler.newFactory());
                }
            }
        }
        return workerGroup;
    }

    /**
     * Sets the worker group.
     *
     * @param workerGroup the I/O event-loop group
     */
    public void setWorkerGroup(EventLoopGroup workerGroup) {
        this.workerGroup = workerGroup;
    }

    /**
     * Returns the accepting-thread count.
     *
     * @return the accepting-thread count
     */
    public int getBossThreadCount() {
        return bossThreadCount;
    }

    /**
     * Sets the accepting-thread count.
     *
     * @param bossThreadCount the accepting-thread count used for lazy group creation
     */
    public void setBossThreadCount(int bossThreadCount) {
        this.bossThreadCount = bossThreadCount;
    }

    /**
     * Returns the I/O-thread count.
     *
     * @return the I/O-thread count
     */
    public int getWorkerThreadCount() {
        return workerThreadCount;
    }

    /**
     * Sets the I/O-thread count.
     *
     * @param workerThreadCount the I/O-thread count used for lazy group creation
     */
    public void setWorkerThreadCount(int workerThreadCount) {
        this.workerThreadCount = workerThreadCount;
    }

    /**
     * Returns the supplied bind address or lazily creates a wildcard address from the configured
     * port.
     *
     * @return the cached bind address
     */
    public InetSocketAddress getSocketAddress() {
        if (socketAddress == null) {
            synchronized (this) {
                if (socketAddress == null) {
                    socketAddress = new InetSocketAddress(portNumber);
                }
            }
        }
        return socketAddress;
    }

    /**
     * Sets the configured bind address.
     *
     * @param socketAddress the bind address
     */
    public void setSocketAddress(InetSocketAddress socketAddress) {
        this.socketAddress = socketAddress;
    }

    /**
     * Returns the configured bind port.
     *
     * @return the configured bind port
     */
    public int getPortNumber() {
        return portNumber;
    }

    /**
     * Sets the configured bind port.
     *
     * @param portNumber the configured bind port
     */
    public void setPortNumber(int portNumber) {
        this.portNumber = portNumber;
    }
}
