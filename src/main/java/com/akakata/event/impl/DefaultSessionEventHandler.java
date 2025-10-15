package com.akakata.event.impl;

import com.akakata.app.PlayerSession;
import com.akakata.app.Session;
import com.akakata.communication.MessageSender;
import com.akakata.event.*;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class will handle any event that gets published to a Session#onEvent(event)
 *
 * @author Kyia
 */
public class DefaultSessionEventHandler implements SessionEventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultSessionEventHandler.class);

    protected final Session session;

    public DefaultSessionEventHandler(Session session) {
        this.session = session;
    }

    @Override
    public int getEventType() {
        return Events.ANY;
    }

    @Override
    public void onEvent(Event event) {
        doEventHandlerMethodLookup(event);
    }

    protected void doEventHandlerMethodLookup(Event event) {
        // Do special handler method lookup
        // LOG.debug("Do the method lookup!");
        int eventType = event.getType();
        switch (eventType) {
            case Events.CONNECT:
                onConnect((ConnectEvent) event);
                break;
            case Events.CONNECT_FAILURE:
                onConnectFailure(event);
                break;
            case Events.LOG_IN_SUCCESS:
                onLoginSuccess(event);
                break;
            case Events.LOG_IN_FAILURE:
                onLoginFailure(event);
                break;
            case Events.SESSION_MESSAGE:
                onDataIn(event);
                break;
            case Events.NETWORK_MESSAGE:
                onNetworkMessage((NetworkEvent) event);
                break;
            case Events.DISCONNECT:
                onDisconnect(event);
                break;
            case Events.EXCEPTION:
                onException(event);
                break;
            case Events.LOG_OUT:
                onLogout(event);
                break;
            default:
                onCustomEvent(event);
                break;
        }
    }

    protected void onDataIn(Event event) {
        LOG.debug("On data in");

        if (session != null) {
            PlayerSession playerSession = (PlayerSession) session;
            NetworkEvent networkEvent = new DefaultNetworkEvent(event);
            // networkEvent.setConnectStrategy(IConnectStrategy.ConnectStrategy.HOLD);
            playerSession.getGame().sendBroadcast(networkEvent);
        }
    }

    protected void onNetworkMessage(NetworkEvent event) {
        LOG.debug("On data out");

        MessageSender sender = session.getSender();
        if (sender != null) {
            sender.sendMessage(event);
        } else {
            LOG.warn("Going to discard event: {} since sender is null in session: {}", event, session);
        }
    }

    protected void onConnect(ConnectEvent event) {
        if (event.getSender() != null) {
            session.setSender(event.getSender());
            // Now send the start event to session
            // session.onEvent(Events.event(null, Events.START));
        } else {
            LOG.warn("Discarding {} as connection is not fully established for this {}", event, session);
        }
    }

    protected void onConnectFailure(Event event) {
        LOG.debug("On connect failed");
    }

    protected void onLoginSuccess(Event event) {
        session.getSender().sendMessage(event);
    }

    protected void onLoginFailure(Event event) {
        session.getSender().sendMessage(event);
    }

    protected void onDisconnect(Event event) {
        LOG.debug("Received disconnect event in session.");
        onException(event);
    }

    protected void onException(Event event) {
        LOG.debug("Received exception/disconnect event in session. Going to close session.");
        onClose(event);
    }

    protected void onLogout(Event event) {
        onClose(event);
    }

    protected void onCustomEvent(Event event) {

    }

    protected void onClose(Event event) {
        session.close();
        ReferenceCountUtil.release(event);
    }

    @Override
    public Session getSession() {
        return session;
    }

    @Override
    public void setSession(Session session) {
        throw new UnsupportedOperationException("Session is a final variable and cannot be reset.");
    }
}
