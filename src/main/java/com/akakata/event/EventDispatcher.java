package com.akakata.event;

import com.akakata.app.Session;

import java.util.List;

/**
 * Registers handlers and delivers events asynchronously.
 *
 * <p>Session-aware delivery selects handlers for the supplied session while still
 * allowing global observers. A shared dispatcher must outlive individual sessions.
 * Use {@link com.akakata.event.impl.EventDispatchers#release(EventDispatcher,
 * com.akakata.app.Session)}
 * for session cleanup rather than closing a shared dispatcher.
 *
 * <p>Dispatching borrows the caller's payload reference. An accepted asynchronous
 * delivery retains its own native reference and releases it after processing or
 * discarding the delivery. The caller can release its original reference after the
 * submission returns. Queue saturation or shutdown may cause a delivery to be dropped.
 *
 * <p>For session-scoped delivery and cleanup:
 * <pre>{@code
 * EventDispatcher dispatcher = EventDispatchers.sharedDispatcher();
 * dispatcher.addHandler(handler);
 * dispatcher.fireEvent(event, session);
 * dispatcher.removeHandlersForSession(session);
 * }</pre>

 * @author Kelvin
 * @see Event
 * @see EventHandler
 * @see com.akakata.event.impl.AgronaEventDispatcher
 * @see com.akakata.event.impl.ShardedEventDispatcher
 */
public interface EventDispatcher {

    // ==================== Handler 管理方法 ====================

    /**
     * Registers a handler according to its type and optional session association.
     *
     * @param eventHandler the handler to register or remove
     * @throws NullPointerException if the handler is null
     */
    void addHandler(EventHandler eventHandler);

    /**
     * Returns the handlers visible for an event-type subscription.
     *
     * <p>Sharded implementations may return the view of one selected shard rather than
     * an aggregate of every shard.
     *
     * @param eventType the event opcode or subscription selector
     * @return the registered handler list, or an empty list if none are visible
     */
    List<EventHandler> getHandlers(int eventType);

    /**
     * Removes the supplied handler registration when present.
     *
     * @param eventHandler the handler to register or remove
     */
    void removeHandler(EventHandler eventHandler);

    /**
     * Removes registrations for the supplied event type from the selected dispatcher scope.
     *
     * @param eventType the event opcode or subscription selector
     */
    void removeHandlersForEvent(int eventType);

    /**
     * Removes handler registrations associated with the supplied session.
     *
     * @param session the session associated with the operation
     * @return {@code true} if at least one handler was removed
     */
    boolean removeHandlersForSession(Session session);

    /**
     * Removes all handler registrations without changing dispatcher ownership.
     */
    void clear();

    // ==================== 事件分发方法 ====================

    /**
     * Submits an event for asynchronous delivery.
     *
     * <p>The overload accepting a session targets that session's handlers and global
     * observers. The default session-aware method delegates to unscoped delivery for
     * implementations that do not supply targeted routing. Native payload ownership
     * remains with the caller; accepted deliveries retain their own references.
     *
     * @param event the event to process
     */
    void fireEvent(Event event);

    /**
     * Submits an event for asynchronous delivery.
     *
     * <p>The overload accepting a session targets that session's handlers and global
     * observers. The default session-aware method delegates to unscoped delivery for
     * implementations that do not supply targeted routing. Native payload ownership
     * remains with the caller; accepted deliveries retain their own references.
     *
     * @param event the event to process
     * @param session the session associated with the operation
     */
    default void fireEvent(Event event, Session session) {
        fireEvent(event);
    }

    // ==================== 生命周期方法 ====================

    /**
     * Stops accepting events and releases resources owned by this dispatcher.
     *
     * <p>Do not call this method for individual-session cleanup when the dispatcher is
     * shared. An implementation may defer completion when called from an event callback.
     */
    void close();
}
