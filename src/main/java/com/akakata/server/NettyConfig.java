package com.akakata.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;

import java.net.InetSocketAddress;
import java.util.Map;

/**
 * @author Kyia
 */
public class NettyConfig {

    protected ChannelInitializer<? extends Channel> channelInitializer;
    private Map<ChannelOption<?>, Object> channelOptions;
    private Map<ChannelOption<?>, Object> channelChildOptions;
    private volatile NioEventLoopGroup bossGroup;
    private volatile NioEventLoopGroup workerGroup;
    private int bossThreadCount;
    private int workerThreadCount;
    private volatile InetSocketAddress socketAddress;
    private int portNumber = 18090;

    public Map<ChannelOption<?>, Object> getChannelOptions() {
        return channelOptions;
    }

    public void setChannelOptions(Map<ChannelOption<?>, Object> channelOptions) {
        this.channelOptions = channelOptions;
    }

    public Map<ChannelOption<?>, Object> getChannelChildOptions() {
        return channelChildOptions;
    }

    public void setChannelChildOptions(Map<ChannelOption<?>, Object> channelChildOptions) {
        this.channelChildOptions = channelChildOptions;
    }

    public NioEventLoopGroup getBossGroup() {
        if (bossGroup == null) {
            synchronized (this) {
                if (bossGroup == null) {
                    bossGroup = (bossThreadCount <= 0)
                            ? new NioEventLoopGroup()
                            : new NioEventLoopGroup(bossThreadCount);
                }
            }
        }
        return bossGroup;
    }

    public void setBossGroup(NioEventLoopGroup bossGroup) {
        this.bossGroup = bossGroup;
    }

    public NioEventLoopGroup getWorkerGroup() {
        if (workerGroup == null) {
            synchronized (this) {
                if (workerGroup == null) {
                    workerGroup = (workerThreadCount <= 0)
                            ? new NioEventLoopGroup()
                            : new NioEventLoopGroup(workerThreadCount);
                }
            }
        }
        return workerGroup;
    }

    public void setWorkerGroup(NioEventLoopGroup workerGroup) {
        this.workerGroup = workerGroup;
    }

    public int getBossThreadCount() {
        return bossThreadCount;
    }

    public void setBossThreadCount(int bossThreadCount) {
        this.bossThreadCount = bossThreadCount;
    }

    public int getWorkerThreadCount() {
        return workerThreadCount;
    }

    public void setWorkerThreadCount(int workerThreadCount) {
        this.workerThreadCount = workerThreadCount;
    }

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

    public void setSocketAddress(InetSocketAddress socketAddress) {
        this.socketAddress = socketAddress;
    }

    public int getPortNumber() {
        return portNumber;
    }

    public void setPortNumber(int portNumber) {
        this.portNumber = portNumber;
    }
}
