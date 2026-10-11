package com.akakata.event.impl;

import com.akakata.app.PlayerSession;
import com.akakata.app.Session;
import com.akakata.communication.MessageSender;
import com.akakata.event.*;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches session events to overridable callbacks by opcode.
 *
 * <p>The default inbound callback broadcasts the payload through the player's
 * game. Outbound callbacks send through the session sender, and disconnect, logout
 * and exception callbacks close the session.
 *
 * @author Kyia
 */
public class DefaultSessionEventHandler implements SessionEventHandler {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(DefaultSessionEventHandler.class);

    /**
     * Session fixed when this handler is constructed.
     */
    protected final Session session;

    /**
     * Associates the handler with a session for its lifetime.
     *
     * @param session the session associated with the operation
     */
    public DefaultSessionEventHandler(Session session) {
        this.session = session;
    }

    /**
     * Subscribes to every event type for the associated session.
     *
     * @return {@link Events#ANY}
     */
    @Override
    public int getEventType() {
        return Events.ANY;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onEvent(Event event) {
        doEventHandlerMethodLookup(event);
    }

    /**
     * Selects the lifecycle, inbound, outbound or custom callback for the event opcode.
     *
     * @param event the event to process
     */
    protected void doEventHandlerMethodLookup(Event event) {
        // Do special handler method lookup
        // LOG.debug("Do the method lookup!");
        int eventType = event.getType();
        switch (eventType) {
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

    /**
     * Wraps the inbound event as a network event and broadcasts it to the player's game.
     *
     * @param event the event to process
     */
    protected void onDataIn(Event event) {
        LOG.debug("On data in");

        if (session != null) {
            PlayerSession playerSession = (PlayerSession) session;
            NetworkEvent networkEvent = new DefaultNetworkEvent(event);
            // networkEvent.setConnectStrategy(IConnectStrategy.ConnectStrategy.HOLD);
            playerSession.getGame().sendBroadcast(networkEvent);
        }
    }

    /**
     * Sends an outbound event through the session sender, or discards it if no sender is attached.
     *
     * @param event the event to process
     */
    protected void onNetworkMessage(NetworkEvent event) {
        LOG.debug("On data out");

        MessageSender sender = session.getSender();
        if (sender != null) {
            sender.sendMessage(event);
        } else {
            LOG.warn("Going to discard event: {} since sender is null in session: {}", event, session);
        }
    }

    /**
     * Sends a successful login event through the session sender.
     *
     * @param event the event to process
     */
    protected void onLoginSuccess(Event event) {
        session.getSender().sendMessage(event);
    }

    /**
     * Sends a failed login event through the session sender.
     *
     * @param event the event to process
     */
    protected void onLoginFailure(Event event) {
        session.getSender().sendMessage(event);
    }

    /**
     * Delegates disconnect handling to the exception callback.
     *
     * @param event the event to process
     */
    protected void onDisconnect(Event event) {
        LOG.debug("Received disconnect event in session.");
        onException(event);
    }

    /**
     * Delegates exception handling to the session-close callback.
     *
     * @param event the event to process
     */
    protected void onException(Event event) {
        LOG.debug("Received exception/disconnect event in session. Going to close session.");
        onClose(event);
    }

    /**
     * Closes the session after a logout event.
     *
     * @param event the event to process
     */
    protected void onLogout(Event event) {
        onClose(event);
    }

    /**
     * Provides an extension hook for unrecognized opcodes. The default implementation does nothing.
     *
     * @param event the event to process
     */
    protected void onCustomEvent(Event event) {

    }

    /**
     * Closes the session and releases the event object if it is reference-counted.
     *
     * @param event the event to process
     */
    protected void onClose(Event event) {
        session.close();
        ReferenceCountUtil.release(event);
    }

    /**
     * Returns the session associated with this handler.
     *
     * @return the session used for targeted delivery and shard selection
     */
    @Override
    public Session getSession() {
        return session;
    }

    /**
     * Rejects changes to the session fixed at construction.
     *
     * @param session the session associated with the operation
     * @throws UnsupportedOperationException always, because the session reference is final
     */
    @Override
    public void setSession(Session session) {
        throw new UnsupportedOperationException("Session is a final variable and cannot be reset.");
    }
}
