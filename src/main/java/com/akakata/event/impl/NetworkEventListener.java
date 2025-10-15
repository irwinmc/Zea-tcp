package com.akakata.event.impl;

import com.akakata.app.Session;
import com.akakata.event.Event;
import com.akakata.event.Events;
import com.akakata.event.SessionEventHandler;

/**
 * @author Kelvin
 */
public class NetworkEventListener implements SessionEventHandler {

    private static final int EVENT_TYPE = Events.NETWORK_MESSAGE;

    private final Session session;

    public NetworkEventListener(Session session) {
        this.session = session;
    }

    @Override
    public void onEvent(Event event) {
        session.onEvent(event);
    }

    @Override
    public int getEventType() {
        return EVENT_TYPE;
    }

    @Override
    public Session getSession() {
        return session;
    }

    @Override
    public void setSession(Session session) {
        throw new UnsupportedOperationException("Session is a final field in this class. It cannot be reset");
    }
}
