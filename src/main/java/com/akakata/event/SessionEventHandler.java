package com.akakata.event;

import com.akakata.app.Session;

/**
 * Associates an event handler with a specific session for targeted delivery and shard selection.
 *
 * <p>The session identity should remain stable while the handler is registered.
 * Global event handlers have no session association and may observe several sessions.
 *
 * @author Kelvin
 * @see EventHandler
 * @see Session
 * @see com.akakata.event.impl.DefaultSessionEventHandler
 * @see com.akakata.event.impl.ShardedEventDispatcher
 */
public interface SessionEventHandler extends EventHandler {

    /**
     * Returns the session associated with this handler.
     *
     * @return the session used for targeted delivery and shard selection
     */
    Session getSession();

    /**
     * Changes the associated session if supported. Remove and register the handler again when
     * changing shard identity.
     *
     * @param session the session associated with the operation
     * @throws UnsupportedOperationException if the session association is immutable
     */
    void setSession(Session session) throws UnsupportedOperationException;
}
