package com.akakata.event;

/**
 * Handles an event type, or all event types when registered with {@link Events#ANY}.
 *
 * <p>Handlers used by concurrent dispatchers must coordinate their own shared state.
 * Native payloads are borrowed during a callback; asynchronous work started by a
 * handler must retain any payload it needs after the callback returns.
 *
 * @author Kelvin
 * @see Event
 * @see EventDispatcher
 * @see SessionEventHandler
 * @see Events
 */
public interface EventHandler {

    /**
     * Handles an event delivered by the dispatcher.
     *
     * <p>The payload is borrowed for the duration of this call. Retain a native reference
     * before handing it to asynchronous work that outlives the callback.
     *
     * @param event the event to process
     */
    void onEvent(Event event);

    /**
     * Returns the subscribed event opcode or the wildcard selector.
     *
     * @return the event type, or {@link Events#ANY} for every type
     */
    int getEventType();
}
