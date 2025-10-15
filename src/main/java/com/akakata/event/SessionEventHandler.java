package com.akakata.event;

import com.akakata.app.Session;

/**
 * @author Kelvin
 */
public interface SessionEventHandler extends EventHandler {

    /**
     * Get session
     *
     * @return session
     */
    Session getSession();

    /**
     * Set session
     *
     * @param session session
     * @throws UnsupportedOperationException Exception of unsupported operation
     */
    void setSession(Session session) throws UnsupportedOperationException;
}
