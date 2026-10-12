package com.akakata.event.impl;

import com.akakata.app.PlayerSession;
import com.akakata.event.Event;

import java.util.Objects;

/**
 * Carries authenticated sender identity alongside a forwarded inbound Game payload.
 *
 * <p>The source and timestamp are copied from the decoded event. The source remains
 * directly accessible for normal native payload retention. Sender identity comes
 * from the logged-in session rather than from client-supplied payload fields.
 */
public final class PlayerMessageEvent extends DefaultEvent {

    /**
     * Serialization version of the event metadata.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Authenticated local sender, excluded from event serialization.
     */
    private final transient PlayerSession playerSession;

    /**
     * Captures sender identity and decoded message metadata without retaining the payload.
     *
     * @param playerSession the authenticated sender session
     * @param event the decoded message borrowed by the forwarding callback
     */
    public PlayerMessageEvent(PlayerSession playerSession, Event event) {
        this.playerSession = Objects.requireNonNull(playerSession, "playerSession");
        Objects.requireNonNull(event, "event");
        super.setType(event.getType());
        super.setSource(event.getSource());
        super.setTimeStamp(event.getTimeStamp());
        setPayloadSize(event.getPayloadSize());
    }

    /**
     * Returns the authenticated local sender of this Game message.
     *
     * @return the sender session, or null after Java deserialization
     */
    public PlayerSession getPlayerSession() {
        return playerSession;
    }
}
