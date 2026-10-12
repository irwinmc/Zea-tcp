package com.akakata.metrics;

import com.akakata.lifecycle.ServerLifecycle;

import java.util.Map;

/**
 * Supplies a fast, nonblocking application health snapshot to presentation layers.
 *
 * @since 0.8.4
 */
@FunctionalInterface
public interface ServerHealth {

    /**
     * Captures health without submitting tasks to the resources being checked.
     *
     * @return current lifecycle and availability results
     */
    Snapshot snapshot();

    /**
     * Immutable health result with named dependency checks.
     *
     * @param phase  application lifecycle phase
     * @param live   whether the application is alive
     * @param ready  whether it can accept business traffic
     * @param checks individual availability results
     */
    record Snapshot(ServerLifecycle.Phase phase, boolean live, boolean ready, Map<String, Boolean> checks) {

        /**
         * Copies the check results so consumers cannot mutate the snapshot.
         *
         * @param phase  application lifecycle phase
         * @param live   liveness result
         * @param ready  readiness result
         * @param checks named availability checks
         */
        public Snapshot {
            java.util.Objects.requireNonNull(phase, "phase");
            checks = Map.copyOf(checks);
        }
    }
}
