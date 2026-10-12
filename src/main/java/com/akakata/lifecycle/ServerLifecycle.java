package com.akakata.lifecycle;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes application lifecycle state independently of HTTP and transport handlers.
 *
 * @since 0.8.4
 */
public final class ServerLifecycle {

    /**
     * States used by startup, shutdown and health consumers.
     */
    public enum Phase {
        /**
         * Resources have not been assembled.
         */
        CREATED,

        /**
         * Application resources are assembled but listeners are not running.
         */
        INITIALIZED,

        /**
         * Configured listeners are being bound.
         */
        STARTING,

        /**
         * Configured listeners started successfully.
         */
        RUNNING,

        /**
         * Shutdown has been requested and readiness is disabled.
         */
        STOPPING,

        /**
         * Cleanup completed successfully.
         */
        TERMINATED,

        /**
         * Startup or cleanup failed.
         */
        FAILED
    }

    /**
     * Published phase; stopping and terminal phases cannot become running again.
     */
    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.CREATED);

    /**
     * Creates an uninitialized lifecycle.
     */
    public ServerLifecycle() {
    }

    /**
     * Returns the current lifecycle phase.
     *
     * @return published phase
     */
    public Phase phase() {
        return phase.get();
    }

    /**
     * Publishes assembly or an empty listener selection if shutdown has not started.
     */
    public void initialized() {
        phase.updateAndGet(current -> current == Phase.CREATED || current == Phase.STARTING
                ? Phase.INITIALIZED : current);
    }

    /**
     * Begins listener startup without reviving a stopped application.
     *
     * @throws IllegalStateException if startup is no longer permitted
     */
    public void starting() {
        while (true) {
            Phase current = phase.get();
            if (current != Phase.CREATED && current != Phase.INITIALIZED) {
                throw new IllegalStateException("Cannot start servers in phase " + current);
            }
            if (phase.compareAndSet(current, Phase.STARTING)) {
                return;
            }
        }
    }

    /**
     * Publishes successful startup if shutdown has not already won the race.
     */
    public void running() {
        phase.compareAndSet(Phase.STARTING, Phase.RUNNING);
    }

    /**
     * Immediately withdraws readiness when shutdown begins.
     */
    public void stopping() {
        phase.updateAndGet(current -> current == Phase.TERMINATED ? current : Phase.STOPPING);
    }

    /**
     * Publishes successful completion of actual cleanup.
     */
    public void terminated() {
        phase.set(Phase.TERMINATED);
    }

    /**
     * Withdraws readiness after startup or cleanup failure.
     */
    public void failed() {
        phase.set(Phase.FAILED);
    }
}
