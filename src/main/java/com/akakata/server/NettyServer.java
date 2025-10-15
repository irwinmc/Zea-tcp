package com.akakata.server;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;

/**
 * @author Kelvin
 */
public interface NettyServer extends Server {

    /**
     * Get channel initializer
     *
     * @return channel initializer
     */
    ChannelInitializer<? extends Channel> getChannelInitializer();

    /**
     * Set channel initializer
     *
     * @param initializer channel initializer
     */
    void setChannelInitializer(ChannelInitializer<? extends Channel> initializer);

    /**
     * Get netty configuration
     *
     * @return config
     */
    NettyConfig getNettyConfig();
}
