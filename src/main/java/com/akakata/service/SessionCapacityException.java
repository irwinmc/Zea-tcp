package com.akakata.service;

/**
 * Rejects a new credential registration when all configured session slots are occupied.
 * Existing credentials can still replace their session without allocating another slot.
 */
public final class SessionCapacityException extends IllegalStateException {

    /**
     * Serialization version for the exception representation.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Describes the exhausted credential-slot budget.
     *
     * @param maximumSessions the configured admission limit
     */
    public SessionCapacityException(int maximumSessions) {
        super("Session capacity reached: " + maximumSessions);
    }
}
