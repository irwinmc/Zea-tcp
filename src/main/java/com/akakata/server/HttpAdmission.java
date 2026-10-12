package com.akakata.server;

import com.akakata.metrics.FlowControlMetrics;
import io.netty.channel.Channel;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounds HTTP sockets within the connection allowance shared by all listeners.
 * One owner is shared by every channel accepted by the HTTP initializer.
 */
public final class HttpAdmission {

    /**
     * Marks an admitted HTTP socket to keep repeated admission idempotent.
     */
    private static final io.netty.util.AttributeKey<HttpAdmission> OWNER =
            io.netty.util.AttributeKey.valueOf("zeaHttpAdmission");

    /**
     * Connection allowance shared with TCP and WebSocket.
     */
    private final ConnectionAdmission total;

    /**
     * HTTP connection and request policy.
     */
    private final HttpLimits limits;

    /**
     * Current HTTP slots, released by channel close futures.
     */
    private final AtomicInteger active = new AtomicInteger();

    /**
     * Creates one HTTP admission owner for a listener.
     *
     * @param total the shared aggregate connection owner
     * @param limits the HTTP-specific allowance and request policy
     */
    public HttpAdmission(ConnectionAdmission total, HttpLimits limits) {
        this.total = Objects.requireNonNull(total);
        this.limits = Objects.requireNonNull(limits);
    }

    /**
     * Claims one HTTP slot and one aggregate slot for a newly activated channel.
     *
     * @param channel the new HTTP connection, admitted exactly once by its lifetime handler
     * @return true if both allowances accepted the connection
     */
    public synchronized boolean admit(Channel channel) {
        if (!channel.isActive()) {
            return false;
        }
        HttpAdmission owner = channel.attr(OWNER).get();
        if (owner == this) {
            return true;
        }
        if (owner != null || !channel.attr(OWNER).compareAndSet(null, this)) {
            return false;
        }
        while (true) {
            int count = active.get();
            if (count >= limits.maximumConnections()) {
                FlowControlMetrics.CONNECTION_REJECTIONS.increment();
                channel.attr(OWNER).compareAndSet(this, null);
                return false;
            }
            if (active.compareAndSet(count, count + 1)) {
                break;
            }
        }
        if (!total.admit(channel)) {
            active.decrementAndGet();
            channel.attr(OWNER).compareAndSet(this, null);
            return false;
        }
        channel.closeFuture().addListener(ignored -> active.decrementAndGet());
        return true;
    }

    /**
     * Returns currently admitted HTTP connections.
     *
     * @return occupied HTTP slots
     */
    public int activeConnections() {
        return active.get();
    }

    /**
     * Returns the validated HTTP lifetime and admission policy.
     *
     * @return the HTTP-specific limits
     */
    public HttpLimits limits() {
        return limits;
    }
}
