package com.akakata.app;

import com.akakata.communication.MessageSender;
import com.akakata.event.Event;
import com.akakata.event.EventDispatcher;
import com.akakata.event.EventHandler;

import java.util.List;

/**
 * @author Kelvin
 */
public interface Session {

    /**
     * Get session id
     *
     * @return id of session
     */
    Object getId();

    /**
     * Set session id
     *
     * @param id id
     */
    void setId(Object id);

    /**
     * Get the attribute of session
     *
     * @param key key of attribute
     * @return value
     */
    Object getAttribute(String key);

    /**
     * Set attribute to session
     *
     * @param key   key of attribute
     * @param value value of attribute
     */
    void setAttribute(String key, Object value);

    /**
     * Remove attribute from session
     *
     * @param key key of attribute
     */
    void removeAttribute(String key);

    /**
     * Handle the event
     *
     * @param event event to proceed
     */
    void onEvent(Event event);

    /**
     * Get the session event dispatcher
     *
     * @return Event dispatcher of session
     */
    EventDispatcher getEventDispatcher();

    /**
     * Get session writable
     *
     * @return writable
     */
    boolean isWritable();

    /**
     * Set session writable
     *
     * @param writable writable
     */
    void setWritable(boolean writable);

    boolean isShuttingDown();

    long getCreationTime();

    long getLastReadWriteTime();

    void setLastReadWriteTime(long lastReadWriteTime);

    Status getStatus();

    void setStatus(Status status);

    boolean isConnected();

    void addHandler(EventHandler eventHandler);

    void removeHandler(EventHandler eventHandler);

    List<EventHandler> getEventHandlers(int eventType);

    void close();

    MessageSender getSender();

    void setSender(MessageSender sender);

    /**
     * Session status
     */
    enum Status {
        // Status of not connected
        NOT_CONNECTED,

        // Status of connecting
        CONNECTING,

        // Status of connected
        CONNECTED,

        // Status of closed
        CLOSED
    }
}
