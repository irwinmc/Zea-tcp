package com.akakata.metrics;

import com.akakata.lifecycle.ServerLifecycle;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Combines lifecycle and injected availability checks without owning their resources.
 *
 * <p>Checks must be fast and nonblocking. A failed or throwing check withdraws
 * readiness. Lifetime traffic counters are deliberately not used as health policy.
 *
 * @since 0.8.4
 */
public final class DefaultServerHealth implements ServerHealth {

    /**
     * Application lifecycle shared with startup and shutdown.
     */
    private final ServerLifecycle lifecycle;

    /**
     * Immutable named checks supplied by the composition root.
     */
    private final Map<String, BooleanSupplier> checks;

    /**
     * Creates health policy independently of concrete Game or network implementations.
     *
     * @param lifecycle application lifecycle
     * @param checks    fast, nonblocking resource checks
     */
    public DefaultServerHealth(ServerLifecycle lifecycle, Map<String, BooleanSupplier> checks) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        LinkedHashMap<String, BooleanSupplier> copy = new LinkedHashMap<>();
        checks.forEach((name, check) -> copy.put(Objects.requireNonNull(name), Objects.requireNonNull(check)));
        this.checks = Collections.unmodifiableMap(copy);
    }

    /**
     * Captures each check once and rereads lifecycle to avoid reporting readiness during shutdown.
     *
     * @return immutable health results; shutdown and unavailable resources are never ready
     */
    @Override
    public Snapshot snapshot() {
        Map<String, Boolean> results = new LinkedHashMap<>();
        checks.forEach((name, check) -> {
            boolean healthy;
            try {
                healthy = check.getAsBoolean();
            } catch (RuntimeException failure) {
                healthy = false;
            }
            results.put(name, healthy);
        });
        ServerLifecycle.Phase phase = lifecycle.phase();
        boolean live = phase == ServerLifecycle.Phase.INITIALIZED || phase == ServerLifecycle.Phase.STARTING
                || phase == ServerLifecycle.Phase.RUNNING || phase == ServerLifecycle.Phase.STOPPING;
        boolean ready = phase == ServerLifecycle.Phase.RUNNING && results.values().stream().allMatch(Boolean::booleanValue);
        return new Snapshot(phase, live, ready, results);
    }
}
