package com.akakata.server;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Samples the listeners selected for a startup attempt without acquiring startup locks.
 *
 * <p>The manager publishes the complete expected set before binding. Health providers
 * can then observe the same set without depending on the manager or HTTP pipeline.
 * Lifecycle phase remains a separate readiness requirement.
 */
@Singleton
public final class ListenerRegistry {

    /**
     * Immutable expected listeners, safely published before any blocking bind operation.
     */
    private volatile Map<String, Server> expected = Map.of();

    /**
     * Creates an initially unavailable listener registry.
     */
    @Inject
    public ListenerRegistry() {
    }

    /**
     * Publishes all listeners required by the next startup attempt.
     *
     * @param listeners protocol names mapped to their nonblocking state providers
     * @throws NullPointerException if the map, a name or a listener is null
     */
    public void expect(Map<String, Server> listeners) {
        expected = Map.copyOf(Objects.requireNonNull(listeners, "listeners"));
    }

    /**
     * Checks every selected listener, treating missing or failed state as unavailable.
     *
     * @return whether at least one listener is selected and all are listening
     */
    public boolean isReady() {
        Map<String, Boolean> states = snapshot();
        return !states.isEmpty() && states.values().stream().allMatch(Boolean::booleanValue);
    }

    /**
     * Captures each selected listener's current bind availability.
     *
     * @return an immutable protocol-to-availability map
     */
    public Map<String, Boolean> snapshot() {
        Map<String, Boolean> result = new LinkedHashMap<>();
        expected.forEach((name, server) -> {
            boolean listening;
            try {
                listening = server.isListening();
            } catch (RuntimeException failure) {
                listening = false;
            }
            result.put(name, listening);
        });
        return Map.copyOf(result);
    }
}
