package com.akakata.context;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the NIO event-loop groups used to accept connections and process network I/O.
 *
 * <p>The groups are shared by the configured servers. Shutdown requests graceful
 * termination for both groups and waits for their termination futures.
 *
 * @author Kelvin
 */
public final class NetworkBootstrap implements AutoCloseable {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(NetworkBootstrap.class);

    /**
     * Shared event-loop group for accepting incoming connections.
     */
    private final EventLoopGroup bossGroup;

    /**
     * Shared event-loop group for processing connection I/O.
     */
    private final EventLoopGroup workerGroup;

    /**
     * Creates accepting and I/O event-loop groups using the supplied thread counts.
     *
     * @param bossThreads the number of accepting threads; zero uses the Netty default
     * @param workerThreads the number of I/O threads; zero uses the Netty default
     */
    public NetworkBootstrap(int bossThreads, int workerThreads) {
        this.bossGroup = new MultiThreadIoEventLoopGroup(
                bossThreads, new DefaultThreadFactory("netty-boss"), NioIoHandler.newFactory());
        this.workerGroup = new MultiThreadIoEventLoopGroup(
                workerThreads, new DefaultThreadFactory("netty-worker"), NioIoHandler.newFactory());

        LOG.info("NetworkBootstrap created with boss={}, worker={} threads",
                bossThreads, workerThreads);
    }

    /**
     * Returns the shared event-loop group accepting server connections.
     *
     * @return the boss event-loop group
     */
    public EventLoopGroup getBossGroup() {
        return bossGroup;
    }

    /**
     * Returns the shared event-loop group processing connection I/O.
     *
     * @return the worker event-loop group
     */
    public EventLoopGroup getWorkerGroup() {
        return workerGroup;
    }

    /**
     * Requests graceful shutdown of both groups and waits uninterruptibly for termination.
     *
     * <p>Call from a thread outside these event loops to avoid waiting for the current loop.
     */
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
