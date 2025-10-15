package com.akakata.event.impl;

import com.akakata.event.EventDispatcher;

/**
 * Factory for creating event dispatchers.
 *
 * @author Kelvin
 */
public class EventDispatchers {

    /**
     * Create a high-performance event dispatcher using Agrona.
     * This is the recommended dispatcher for production use.
     *
     * @return initialized event dispatcher
     */
    public static EventDispatcher newAgronaEventDispatcher() {
        AgronaEventDispatcher dispatcher = new AgronaEventDispatcher();
        dispatcher.initialize();
        return dispatcher;
    }
}
