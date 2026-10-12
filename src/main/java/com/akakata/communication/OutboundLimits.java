package com.akakata.communication;

/**
 * Bounds socket writes before encoding and encoded buffers awaiting completion.
 *
 * @param maximumPendingWrites       the positive number of admitted application writes
 * @param maximumPendingBytes        the positive encoded-buffer budget per connection
 * @param maximumPendingPayloadBytes the positive source-payload budget before write completion
 */
public record OutboundLimits(int maximumPendingWrites, int maximumPendingBytes, long maximumPendingPayloadBytes) {

    /**
     * Default per-connection admission and encoded-buffer budgets.
     */
    public static final OutboundLimits DEFAULT = new OutboundLimits(1_024, 1_048_576, 1_048_576);

    /**
     * Creates legacy count and encoded-byte limits with a 1 MiB source-payload budget.
     *
     * @param maximumPendingWrites the positive pending-write allowance
     * @param maximumPendingBytes  the positive encoded-byte allowance
     */
    public OutboundLimits(int maximumPendingWrites, int maximumPendingBytes) {
        this(maximumPendingWrites, maximumPendingBytes, 1_048_576);
    }

    /**
     * Validates positive write and byte budgets.
     *
     * @param maximumPendingWrites       the maximum pending application writes
     * @param maximumPendingBytes        the maximum pending encoded bytes
     * @param maximumPendingPayloadBytes the maximum pending source estimates
     * @throws IllegalArgumentException if either budget is not positive
     */
    public OutboundLimits {
        if (maximumPendingWrites <= 0 || maximumPendingBytes <= 0 || maximumPendingPayloadBytes <= 0) {
            throw new IllegalArgumentException("Outbound budgets must be positive");
        }
    }
}
