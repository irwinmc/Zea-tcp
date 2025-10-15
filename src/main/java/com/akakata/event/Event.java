package com.akakata.event;

/**
 * @author Kelvin
 */
public interface Event {

    /**
     * Get event type
     *
     * @return type
     */
    int getType();

    /**
     * Set event type
     *
     * @param type type
     */
    void setType(int type);

    /**
     * Get event source
     *
     * @return source
     */
    Object getSource();

    /**
     * Set event source
     *
     * @param source source
     */
    void setSource(Object source);

    /**
     * Get event time
     *
     * @return time
     */
    long getTimeStamp();

    /**
     * Set event time
     *
     * @param timeStamp time
     */
    void setTimeStamp(long timeStamp);
}
