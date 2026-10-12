package com.akakata.context;

import com.akakata.lifecycle.AsyncCloseable;
import com.akakata.lifecycle.ShutdownSupport;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the NIO event-loop groups used to accept connections and process network I/O.
 *
 * <p>The groups are shared by the configured servers. Shutdown requests graceful
 * termination for both groups. Worker callers only request shutdown; external
 * callers wait within a deadline, while {@link #closeAsync()} observes actual termination.
 *
 * @author Kelvin
 */
public final class NetworkBootstrap implements AsyncCloseable {

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
     * External waiting interval and Netty shutdown policy in milliseconds.
     */
    private final long shutdownMillis;

    /**
     * Ensures both group shutdown requests are issued once.
     */
    private final AtomicBoolean closing = new AtomicBoolean();

    /**
     * Actual termination of both owned groups.
     */
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();

    /**
     * Creates accepting and I/O event-loop groups using the supplied thread counts.
     *
     * @param bossThreads   the number of accepting threads; zero uses the Netty default
     * @param workerThreads the number of I/O threads; zero uses the Netty default
     */
    public NetworkBootstrap(int bossThreads, int workerThreads) {
        this(bossThreads, workerThreads, ShutdownSupport.DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * Creates network groups with an explicit external shutdown waiting interval.
     *
     * @param bossThreads    accepting threads, or zero for the Netty default
     * @param workerThreads  I/O threads, or zero for the Netty default
     * @param shutdownMillis positive shutdown interval in milliseconds
     * @throws IllegalArgumentException if a count is negative or the interval is not positive
     */
    public NetworkBootstrap(int bossThreads, int workerThreads, long shutdownMillis) {
        if (bossThreads < 0 || workerThreads < 0 || shutdownMillis <= 0) {
            throw new IllegalArgumentException("Invalid network thread counts or shutdown interval");
        }
        this.shutdownMillis = shutdownMillis;
        this.bossGroup = new MultiThreadIoEventLoopGroup(
                bossThreads, ShutdownSupport.workerFactory(new DefaultThreadFactory("netty-boss")), NioIoHandler.newFactory());
        this.workerGroup = new MultiThreadIoEventLoopGroup(
                workerThreads, ShutdownSupport.workerFactory(new DefaultThreadFactory("netty-worker")), NioIoHandler.newFactory());

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
     * Requests shutdown and waits within the configured interval outside workers.
     *
     * @throws com.akakata.lifecycle.ShutdownTimeoutException if termination exceeds the interval
     */
    @Override
    public void close() {
        ShutdownSupport.await(closeAsync(), shutdownMillis, "network event loops");
    }

    /**
     * Requests both group shutdowns without waiting for any event-loop callback.
     *
     * @return an independent observation of actual group termination
     */
    @Override
    public CompletableFuture<Void> closeAsync() {
        if (closing.compareAndSet(false, true)) {
            LOG.info("Shutting down network event loops");
            bossGroup.shutdownGracefully(0, shutdownMillis, TimeUnit.MILLISECONDS);
            workerGroup.shutdownGracefully(0, shutdownMillis, TimeUnit.MILLISECONDS);
            CompletableFuture.allOf(termination(bossGroup), termination(workerGroup)).whenComplete((ignored, failure) -> {
                if (failure == null) {
                    LOG.info("Network event loops terminated");
                    ShutdownSupport.complete(closeCompletion, null);
                } else {
                    ShutdownSupport.complete(closeCompletion, failure);
                }
            });
        }
        return closeCompletion.copy();
    }

    /**
     * Exposes network availability without requesting cleanup.
     *
     * @return whether either group is shutting down
     */
    public boolean isClosing() {
        return closing.get() || bossGroup.isShuttingDown() || workerGroup.isShuttingDown();
    }

    /**
     * Converts a group's actual termination signal without blocking its workers.
     *
     * @param group owned group
     * @return termination completion
     */
    private static CompletableFuture<Void> termination(EventLoopGroup group) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        group.terminationFuture().addListener(future -> {
            if (future.isSuccess()) {
                ShutdownSupport.complete(completion, null);
            } else {
                ShutdownSupport.complete(completion, future.cause());
            }
        });
        return completion;
    }
}
