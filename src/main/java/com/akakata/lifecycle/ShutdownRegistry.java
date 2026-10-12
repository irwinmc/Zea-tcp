package com.akakata.lifecycle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Tracks ordered resource ownership while assembly may race with shutdown.
 *
 * <p>Resources registered after a close request start cleanup immediately. Sealing
 * registration makes the complete owned set observable. No application cleanup is
 * invoked under the registry monitor, and each identity is closed at most once.
 *
 * @since 0.8.4
 */
public final class ShutdownRegistry {

    /**
     * Registered ownership entries keyed by resource identity.
     */
    private final IdentityHashMap<Object, Registration> resources = new IdentityHashMap<>();

    /**
     * Shared sequence observation interval.
     */
    private final long shutdownMillis;

    /**
     * Completion of every registered cleanup action after registration is sealed.
     */
    private final CompletableFuture<Void> completion = new CompletableFuture<>();

    /**
     * Whether cleanup has been requested, guarded by the registry.
     */
    private boolean closing;

    /**
     * Whether assembly can no longer publish resources, guarded by the registry.
     */
    private boolean sealed;

    /**
     * Whether aggregate completion has been attached to the final ownership set.
     */
    private boolean observing;

    /**
     * Creates an ownership registry with a positive observation interval.
     *
     * @param shutdownMillis positive interval in milliseconds
     * @throws IllegalArgumentException if the interval is not positive
     */
    public ShutdownRegistry(long shutdownMillis) {
        if (shutdownMillis <= 0) {
            throw new IllegalArgumentException("Shutdown registry interval must be positive");
        }
        this.shutdownMillis = shutdownMillis;
    }

    /**
     * Accepts a returned resource before exposing it to the rest of an application.
     *
     * <p>A late registration is still owned and starts cleanup immediately. The caller
     * retains ownership if registration is already sealed and this method throws.
     *
     * @param <T> resource type
     * @param resource returned resource
     * @param name diagnostic resource name
     * @param order preferred cleanup order, lowest first
     * @param close cleanup operation preserving application overrides
     * @return the accepted resource
     * @throws IllegalStateException if registration is sealed
     */
    public <T> T own(T resource, String name, int order, Consumer<? super T> close) {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(close, "close");
        Registration registration;
        boolean start;
        synchronized (this) {
            if (sealed) {
                throw new IllegalStateException("Resource registration is sealed");
            }
            registration = resources.get(resource);
            if (registration == null) {
                registration = new Registration(Objects.requireNonNull(name, "name"), order, () -> close.accept(resource));
                resources.put(resource, registration);
            }
            start = closing;
        }
        if (start) {
            registration.start();
        }
        return resource;
    }

    /**
     * Declares that initialization has returned all resources it can allocate.
     */
    public void seal() {
        synchronized (this) {
            sealed = true;
        }
        observeCompletion();
    }

    /**
     * Starts ordered cleanup of currently owned resources without waiting for assembly.
     *
     * @return an independent observation including late registrations
     */
    public CompletableFuture<Void> closeAsync() {
        List<Registration> snapshot;
        synchronized (this) {
            if (closing) {
                return completion.copy();
            }
            closing = true;
            snapshot = resources.values().stream().sorted(Comparator.comparingInt(entry -> entry.order)).toList();
        }
        List<ShutdownSequence.Step> steps = new ArrayList<>();
        for (Registration entry : snapshot) {
            steps.add(new ShutdownSequence.Step(entry.name,
                    () -> ShutdownSupport.await(entry.start(), shutdownMillis, entry.name)));
        }
        ShutdownSequence.start(steps, shutdownMillis);
        observeCompletion();
        return completion.copy();
    }

    /**
     * Observes the final ownership set outside the registry monitor.
     */
    private void observeCompletion() {
        CompletableFuture<?>[] actions;
        synchronized (this) {
            if (!closing || !sealed || observing) {
                return;
            }
            observing = true;
            actions = resources.values().stream().map(entry -> entry.completion).toArray(CompletableFuture[]::new);
        }
        CompletableFuture.allOf(actions).whenComplete((ignored, failure) -> ShutdownSupport.complete(completion, failure));
    }

    /**
     * Records one resource without depending on its implementation type.
     */
    private static final class Registration {

        /**
         * Diagnostic name.
         */
        private final String name;

        /**
         * Preferred cleanup order.
         */
        private final int order;

        /**
         * Actual application cleanup action.
         */
        private final Runnable close;

        /**
         * Exactly-once invocation guard.
         */
        private final AtomicBoolean started = new AtomicBoolean();

        /**
         * Actual action completion, independent of observation deadlines.
         */
        private final CompletableFuture<Void> completion = new CompletableFuture<>();

        /**
         * Captures ownership and ordering metadata.
         *
         * @param name diagnostic name
         * @param order preferred cleanup order
         * @param close application cleanup
         */
        private Registration(String name, int order, Runnable close) {
            this.name = name;
            this.order = order;
            this.close = close;
        }

        /**
         * Invokes cleanup once outside callers and registry locks.
         *
         * @return actual action completion
         */
        private CompletableFuture<Void> start() {
            if (started.compareAndSet(false, true)) {
                ShutdownSupport.runAsync("zea-resource-" + name, close)
                        .whenComplete((ignored, failure) -> ShutdownSupport.complete(completion, failure));
            }
            return completion;
        }
    }
}
