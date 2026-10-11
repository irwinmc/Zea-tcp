package com.akakata.app;

import com.akakata.communication.MessageSender;
import com.akakata.event.Event;
import com.akakata.event.EventDispatcher;
import com.akakata.event.EventHandler;

import java.util.List;

/**
 * Represents a connection lifecycle, attributes, event handlers and an outbound sender.
 *
 * <p>A session dispatcher may be shared by several sessions. Closing a session must
 * release its own handlers without shutting down shared dispatchers. The default
 * implementation uses the session monitor to coordinate login, connection and closure.
 *
 * <p>Reference-counted event payloads remain owned by the caller. Asynchronous
 * dispatchers retain their own references for accepted deliveries.
 *
 * @author Kelvin
 */
public interface Session {

    /**
     * Returns the identifier used for session identity and shard selection.
     *
     * @return the session identifier
     */
    Object getId();

    /**
     * Requests a change to the session identifier. Implementations may keep identifiers immutable.
     *
     * @param id the identifier
     */
    void setId(Object id);

    /**
     * Returns the value stored under an attribute key.
     *
     * @param key key of attribute
     * @return the stored value, or {@code null} if the key is absent or its value is null
     */
    Object getAttribute(String key);

    /**
     * Stores an attribute value under the supplied key.
     *
     * @param key key of attribute
     * @param value value of attribute
     */
    void setAttribute(String key, Object value);

    /**
     * Removes the value stored under an attribute key.
     *
     * @param key key of attribute
     */
    void removeAttribute(String key);

    /**
     * Submits an event for this session. A closed default session ignores the submission.
     *
     * <p>Accepted asynchronous deliveries retain their own native payload reference;
     * the caller remains responsible for its original reference.
     *
     * @param event event to proceed
     */
    void onEvent(Event event);

    /**
     * Returns the dispatcher used by this session. It may be shared with other sessions.
     *
     * @return the associated dispatcher
     */
    EventDispatcher getEventDispatcher();

    /**
     * Returns the session's outbound-write eligibility flag.
     *
     * @return {@code true} if the session is marked writable
     */
    boolean isWritable();

    /**
     * Updates outbound-write eligibility. A closed default session remains nonwritable.
     *
     * @param writable whether outbound writes are permitted
     */
    void setWritable(boolean writable);

    /**
     * Tests whether session closure has started.
     *
     * @return {@code true} once closure starts
     */
    boolean isShuttingDown();

    /**
     * Returns the creation timestamp in epoch milliseconds.
     *
     * @return the creation timestamp in milliseconds
     */
    long getCreationTime();

    /**
     * Returns the last recorded activity timestamp in epoch milliseconds.
     *
     * @return the last recorded activity timestamp in milliseconds
     */
    long getLastReadWriteTime();

    /**
     * Updates the recorded activity timestamp in epoch milliseconds.
     *
     * @param lastReadWriteTime the activity timestamp in epoch milliseconds
     */
    void setLastReadWriteTime(long lastReadWriteTime);

    /**
     * Returns the current connection state.
     *
     * @return the current session status
     */
    Status getStatus();

    /**
     * Updates the connection state. The closed state is terminal in the default implementation.
     *
     * @param status the connection state or HTTP response status
     */
    void setStatus(Status status);

    /**
     * Tests whether the session status is connected.
     *
     * @return {@code true} if the status is {@link Session.Status#CONNECTED}
     */
    boolean isConnected();

    /**
     * Registers a handler with the session dispatcher. A closed default session ignores
     * registration.
     *
     * @param eventHandler the handler to register or remove
     */
    void addHandler(EventHandler eventHandler);

    /**
     * Removes a handler from the session dispatcher.
     *
     * @param eventHandler the handler to register or remove
     */
    void removeHandler(EventHandler eventHandler);

    /**
     * Returns handlers visible to this session's dispatcher for the supplied event type.
     *
     * @param eventType the event opcode or subscription selector
     * @return the handlers registered for the type; the dispatcher may also serve other sessions
     */
    List<EventHandler> getEventHandlers(int eventType);

    /**
     * Closes the session, releases its handlers and closes its sender.
     *
     * <p>Implementations should make repeated calls safe. Shared dispatcher resources
     * must remain available to other sessions.
     */
    void close();

    /**
     * Returns the current outbound sender.
     *
     * @return the sender, or {@code null} if no sender is attached
     */
    MessageSender getSender();

    /**
     * Attaches an outbound sender.
     *
     * <p>The default implementation closes a replaced sender outside the session
     * monitor. A sender supplied after closure is closed immediately and is not attached.
     *
     * @param sender the outbound sender, possibly null
     */
    void setSender(MessageSender sender);

    /**
     * Describes the connection state of a session. {@link #CLOSED} is terminal in the default
     * implementation.
     */
    enum Status {
        // Status of not connected

        /**
         * The session has not entered the connection process.
         */
        NOT_CONNECTED,

        // Status of connecting

        /**
         * The session is being attached to a game.
         */
        CONNECTING,

        // Status of connected

        /**
         * The session is attached and connected.
         */
        CONNECTED,

        // Status of closed

        /**
         * The session is closed; the default implementation treats this state as terminal.
         */
        CLOSED
    }
}
