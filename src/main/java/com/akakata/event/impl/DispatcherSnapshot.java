package com.akakata.event.impl;

/**
 * Captures one executor's admission budgets, outcomes and approximate timing percentiles.
 *
 * @param queued pending tasks excluding the running callback
 * @param pendingBytes estimated payload bytes including the running callback
 * @param capacity maximum pending tasks
 * @param byteBudget maximum admitted payload bytes
 * @param accepted cumulative accepted deliveries
 * @param completed cumulative executed deliveries
 * @param cancelled cumulative deliveries cancelled before execution
 * @param rejected cumulative saturation and executor rejection count
 * @param waitP95Micros approximate queue wait P95 bucket upper bound
 * @param waitP99Micros approximate queue wait P99 bucket upper bound
 * @param executionP95Micros approximate callback duration P95 bucket upper bound
 * @param executionP99Micros approximate callback duration P99 bucket upper bound
 */
public record DispatcherSnapshot(int queued, long pendingBytes, int capacity, long byteBudget,
        long accepted, long completed, long cancelled, long rejected,
        long waitP95Micros, long waitP99Micros, long executionP95Micros, long executionP99Micros) {}
