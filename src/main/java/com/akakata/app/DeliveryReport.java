package com.akakata.app;

/**
 * Reports admission of an explicit Game send to recipient session queues.
 *
 * <p>Acceptance is not a network delivery acknowledgment. Closed or disconnected
 * recipients are skipped; overloaded recipients are rejected and closed by the
 * default Game so later state updates cannot follow a missing update.
 *
 * @param accepted the number of recipient queues that accepted the event
 * @param rejected the number of open recipients whose queues rejected the event
 * @param skipped  the number of recipients no longer connected to this Game
 */
public record DeliveryReport(int accepted, int rejected, int skipped) {

    /**
     * Validates the nonnegative recipient counts.
     *
     * @param accepted the accepted recipient count
     * @param rejected the rejected recipient count
     * @param skipped  the skipped recipient count
     * @throws IllegalArgumentException if any count is negative
     */
    public DeliveryReport {
        if (accepted < 0 || rejected < 0 || skipped < 0) {
            throw new IllegalArgumentException("Recipient counts must be nonnegative");
        }
    }
}
