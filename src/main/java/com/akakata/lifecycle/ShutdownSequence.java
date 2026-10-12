package com.akakata.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Initiates named cleanup stages in order without letting one stage block the rest.
 *
 * <p>The sequence waits within one shared budget, then still initiates every remaining
 * stage. Completion tracks all actual actions; an expired observation never cancels
 * an action or reports unfinished cleanup as successful.
 *
 * @since 0.8.4
 */
public final class ShutdownSequence {

    /**
     * Logger for stage failures and expired observation deadlines.
     */
    private static final Logger LOG = LoggerFactory.getLogger(ShutdownSequence.class);

    /**
     * Named action with no dependency on a particular application component.
     *
     * @param name   diagnostic stage name
     * @param action cleanup operation
     */
    public record Step(String name, Runnable action) {

        /**
         * Validates stage metadata.
         *
         * @param name   diagnostic name
         * @param action cleanup operation
         */
        public Step {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(action, "action");
        }
    }

    /**
     * Prevents construction of this utility.
     */
    private ShutdownSequence() {
    }

    /**
     * Starts an ordered sequence on a dedicated virtual coordinator.
     *
     * @param steps         snapshot of stages to initiate
     * @param timeoutMillis positive shared observation budget
     * @return completion of every action, exceptional if any action fails
     */
    public static CompletableFuture<Void> start(List<Step> steps, long timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("Shutdown sequence interval must be positive");
        }
        List<Step> snapshot = List.copyOf(steps);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        ShutdownSupport.runAsync("zea-shutdown-coordinator", () -> {
            List<CompletableFuture<Void>> actions = new ArrayList<>();
            long started = System.nanoTime();
            long budget = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            for (Step step : snapshot) {
                CompletableFuture<Void> action = ShutdownSupport.runAsync("zea-close-" + step.name(), step.action());
                actions.add(action);
                long remaining = budget - (System.nanoTime() - started);
                if (remaining > 0) {
                    try {
                        ShutdownSupport.await(action, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)), step.name());
                    } catch (IllegalStateException failure) {
                        LOG.warn("Shutdown stage {} did not finish successfully within its observation interval", step.name(), failure);
                    }
                }
            }
            CompletableFuture.allOf(actions.toArray(CompletableFuture[]::new)).whenComplete((ignored, failure) -> {
                if (failure == null) {
                    ShutdownSupport.complete(completion, null);
                } else {
                    ShutdownSupport.complete(completion, failure);
                }
            });
        }).exceptionally(failure -> {
            ShutdownSupport.complete(completion, failure);
            return null;
        });
        return completion;
    }
}
