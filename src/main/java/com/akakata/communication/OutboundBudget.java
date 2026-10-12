package com.akakata.communication;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounds outstanding source estimates and encoded bytes across a set of sockets.
 *
 * <p>Source and encoded reservations coexist while both resources are retained.
 * Shared broadcast sources are charged per recipient as logical write pressure.
 * This is an admission budget, not an exact process-memory or kernel-buffer limit.
 */
public final class OutboundBudget {

    /**
     * Default aggregate outstanding-byte allowance of 256 MiB.
     */
    public static final long DEFAULT_MAXIMUM_BYTES = 256L * 1024 * 1024;

    /**
     * Shared allowance for standalone senders outside a ServerContext.
     */
    public static final OutboundBudget DEFAULT = new OutboundBudget(DEFAULT_MAXIMUM_BYTES);

    /**
     * Positive aggregate admission limit.
     */
    private final long maximumBytes;

    /**
     * Reserved estimates and encoded bytes awaiting completion.
     */
    private final AtomicLong pendingBytes = new AtomicLong();

    /**
     * Creates a budget shared by every socket in an application context.
     *
     * @param maximumBytes the positive aggregate byte allowance
     * @throws IllegalArgumentException if the allowance is nonpositive
     */
    public OutboundBudget(long maximumBytes) {
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("Aggregate outbound budget must be positive");
        }
        this.maximumBytes = maximumBytes;
    }

    /**
     * Atomically claims bytes without waiting or overflowing the counter.
     *
     * @param bytes a nonnegative charge
     * @return true if the reservation was accepted
     * @throws IllegalArgumentException if the charge is negative
     */
    public boolean reserve(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("Outbound charge must be nonnegative");
        }
        while (true) {
            long current = pendingBytes.get();
            if (bytes > maximumBytes - current) {
                return false;
            }
            if (pendingBytes.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    /**
     * Returns one previously accepted reservation after its resource is released.
     *
     * @param bytes the exact charge previously reserved
     * @throws IllegalArgumentException if the charge is negative or exceeds reservations
     */
    public void release(long bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("Outbound charge must be nonnegative");
        }
        while (true) {
            long current = pendingBytes.get();
            if (bytes > current) {
                throw new IllegalArgumentException("Unbalanced outbound release");
            }
            if (pendingBytes.compareAndSet(current, current - bytes)) {
                return;
            }
        }
    }

    /**
     * Returns the aggregate outstanding charge.
     *
     * @return estimated source bytes plus encoded pending bytes
     */
    public long pendingBytes() {
        return pendingBytes.get();
    }

    /**
     * Returns the configured aggregate allowance.
     *
     * @return the maximum outstanding charge
     */
    public long maximumBytes() {
        return maximumBytes;
    }
}
