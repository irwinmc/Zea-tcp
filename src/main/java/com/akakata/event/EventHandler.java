package com.akakata.event;

/**
 * @author Kelvin
 */
public interface EventHandler {

    /**
     * On event
     *
     * @param event Event to fire
     */
    void onEvent(Event event);

    /**
     * Get event type
     *
     * @return The type of event
     */
    int getEventType();
}
