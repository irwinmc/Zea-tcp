package com.akakata.event;

/**
 * Carries an event type, a payload or source object, and a caller-defined timestamp.
 *
 * <p>The factories in {@link Events} use epoch milliseconds. Callers may supply
 * a different timestamp convention when all consumers agree. Events are mutable;
 * callers should not change routing information while an event is being dispatched.
 * Reference-counted payload ownership is managed separately by dispatchers and senders.
 *
 * @author Kelvin
 * @see Events
 * @see com.akakata.event.impl.DefaultEvent
 * @see EventHandler
 */
public interface Event {

    /**
     * Returns the event opcode used for handler selection.
     *
     * @return the event type
     */
    int getType();

    /**
     * Sets the event opcode. Fixed-type event implementations may reject changes.
     *
     * @param type the event opcode
     * @throws IllegalArgumentException if the implementation fixes its event type
     */
    void setType(int type);

    /**
     * Returns the payload or source object without retaining native references.
     *
     * @return the source object, possibly {@code null}
     */
    Object getSource();

    /**
     * Replaces the payload reference without retaining the new source or releasing the old one.
     *
     * @param source the payload or source object, possibly null
     */
    void setSource(Object source);

    /**
     * Returns the event timestamp. Factory-created events use epoch milliseconds.
     *
     * @return the timestamp in the convention selected by the producer
     */
    long getTimeStamp();

    /**
     * Sets the timestamp using a convention understood by this event's consumers.
     *
     * @param timeStamp the producer-defined timestamp; factories use epoch milliseconds
     */
    void setTimeStamp(long timeStamp);
}
