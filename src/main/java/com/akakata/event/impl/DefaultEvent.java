package com.akakata.event.impl;

import com.akakata.event.Event;

import java.io.Serializable;

/**
 * Stores mutable event type, source and timestamp values without owning the source reference.
 *
 * @author Kelvin
 * @see com.akakata.event.Event
 * @see com.akakata.event.Events
 * @see DefaultNetworkEvent
 */
public class DefaultEvent implements Event, Serializable {

    /**
     * Creates a default event instance.
     */
    public DefaultEvent() {
    }

    /**
     * Serialization version identifier.
     */
    private static final long serialVersionUID = 4184094945897413320L;

    // ==================== 字段 ====================

    /**
     * Event opcode used for routing and handler selection.
     *
     * @serial Event opcode used for routing and handler selection.
     */
    protected int type;

    /**
     * Payload or origin object retained as an ordinary Java reference.
     *
     * @serial Payload or origin object retained as an ordinary Java reference.
     */
    protected Object source;

    /**
     * Producer-defined event timestamp; event factories use epoch milliseconds.
     *
     * @serial Producer-defined event timestamp; event factories use epoch milliseconds.
     */
    protected long timeStamp;

    /**
     * Captured byte estimate, or -1 when derived from the source.
     *
     * @serial Captured payload charge used for executor admission.
     */
    private long payloadSize = -1;

    /**
     * {@inheritDoc}
     */
    @Override
    public long getPayloadSize() {
        return payloadSize < 0 ? Event.super.getPayloadSize() : payloadSize;
    }

    /**
     * Captures a conservative payload charge before event publication.
     *
     * @param bytes the nonnegative estimated storage
     * @throws IllegalArgumentException if the charge is negative
     */
    public void setPayloadSize(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative payload charge");
        payloadSize = bytes;
    }

    // ==================== Getter/Setter ====================

    /**
     * Returns the event opcode used for handler selection.
     *
     * @return the event type
     */
    @Override
    public int getType() {
        return type;
    }

    /**
     * Sets the event opcode. Fixed-type event implementations may reject changes.
     *
     * @param type the event opcode
     * @throws IllegalArgumentException if the implementation fixes its event type
     */
    @Override
    public void setType(int type) {
        this.type = type;
    }

    /**
     * Returns the payload or source object without retaining native references.
     *
     * @return the source object, possibly {@code null}
     */
    @Override
    public Object getSource() {
        return source;
    }

    /**
     * Replaces the payload reference without retaining the new source or releasing the old one.
     *
     * @param source the payload or source object, possibly null
     */
    @Override
    public void setSource(Object source) {
        this.source = source;
        payloadSize = -1;
    }

    /**
     * Returns the event timestamp. Factory-created events use epoch milliseconds.
     *
     * @return the timestamp in the convention selected by the producer
     */
    @Override
    public long getTimeStamp() {
        return timeStamp;
    }

    /**
     * Sets the timestamp using a convention understood by this event's consumers.
     *
     * @param timeStamp the producer-defined timestamp; factories use epoch milliseconds
     */
    @Override
    public void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }

    // ==================== Object 方法 ====================

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        return "Event [type=" + type + ", source=" + source + ", timeStamp=" + timeStamp + "]";
    }
}
