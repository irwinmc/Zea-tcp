package com.akakata.server;

import com.akakata.metrics.FlowControlMetrics;
import io.netty.channel.Channel;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shares an atomic socket budget across TCP and WebSocket listeners in one context.
 * Each admitted socket returns its slot exactly once when its close future completes.
 */
public final class ConnectionAdmission {

    /**
     * Configured lifetime and admission policy.
     */
    private final TransportLimits limits;

    /**
     * Current socket slots including unauthenticated clients.
     */
    private final AtomicInteger active = new AtomicInteger();

    /**
     * Creates a per-context connection budget.
     *
     * @param limits the validated transport policy
     */
    public ConnectionAdmission(TransportLimits limits) {
        this.limits = java.util.Objects.requireNonNull(limits);
    }

    /**
     * Claims a socket slot and attaches close-future cleanup atomically.
     *
     * @param channel a newly active socket
     * @return true if admitted; a rejected caller must close the socket
     */
    public boolean admit(Channel channel) {
        while (true) {
            int count = active.get();
            if (count >= limits.maximumConnections()) {
                FlowControlMetrics.CONNECTION_REJECTIONS.increment();
                return false;
            }
            if (active.compareAndSet(count, count + 1)) break;
        }
        FlowControlMetrics.ACTIVE_CONNECTIONS.increment();
        channel.closeFuture().addListener(ignored -> {
            active.decrementAndGet();
            FlowControlMetrics.ACTIVE_CONNECTIONS.decrement();
        });
        return true;
    }

    /**
     * Returns occupied socket slots.
     *
     * @return currently admitted connections
     */
    public int activeConnections() {
        return active.get();
    }

    /**
     * Returns the shared transport policy.
     *
     * @return validated limits
     */
    public TransportLimits limits() {
        return limits;
    }
}
