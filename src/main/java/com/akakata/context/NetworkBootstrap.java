package com.akakata.context;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages Netty network resources (EventLoopGroups).
 * Single responsibility: Network resource lifecycle.
 *
 * @author Kelvin
 */
public final class NetworkBootstrap implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(NetworkBootstrap.class);

    private final NioEventLoopGroup bossGroup;
    private final NioEventLoopGroup workerGroup;

    public NetworkBootstrap(int bossThreads, int workerThreads) {
        this.bossGroup = new NioEventLoopGroup(bossThreads, new DefaultThreadFactory("netty-boss"));
        this.workerGroup = new NioEventLoopGroup(workerThreads, new DefaultThreadFactory("netty-worker"));

        LOG.info("NetworkBootstrap created with boss={}, worker={} threads",
                bossThreads, workerThreads);
    }

    public NioEventLoopGroup getBossGroup() {
        return bossGroup;
    }

    public NioEventLoopGroup getWorkerGroup() {
        return workerGroup;
    }

    @Override
    public void close() {
        LOG.info("Shutting down network event loops");
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        bossGroup.terminationFuture().syncUninterruptibly();
        workerGroup.terminationFuture().syncUninterruptibly();
        LOG.info("Network event loops terminated");
    }
}
