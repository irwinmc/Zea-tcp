package com.akakata.app.impl;

import com.akakata.app.Session;
import com.akakata.communication.MessageSender;
import com.akakata.event.Event;
import com.akakata.event.EventDispatcher;
import com.akakata.event.EventHandler;
import com.akakata.event.impl.EventDispatchers;
import com.akakata.service.IdGeneratorService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Kelvin
 */
public class DefaultSession implements Session {

    /**
     * Session id
     */
    protected final Object id;

    /**
     * session parameters
     */
    protected final Map<String, Object> sessionAttributes;

    /**
     * Session created time
     */
    protected final long creationTime;

    /**
     * Event dispatcher
     */
    protected EventDispatcher eventDispatcher;

    /**
     * Session last read write time
     */
    protected long lastReadWriteTime;

    /**
     * Status
     */
    protected Status status;

    /**
     * Writable
     */
    protected boolean isWritable;

    /**
     * Life cycle variable to check if the session is shutting down. If it is, then no
     * more incoming events will be accepted.
     */
    protected volatile boolean isShuttingDown;

    /**
     * Message sender
     */
    protected MessageSender sender = null;

    /**
     * Constructor of Default session
     *
     * @param sessionBuilder session builder
     */
    protected DefaultSession(SessionBuilder sessionBuilder) {
        // validate variables and provide default values if necessary. Normally
        // done in the builder.build() method, but done here since this class is
        // meant to be overriden and this could be easier.
        sessionBuilder.validateAndSetValues();

        this.id = sessionBuilder.id;
        this.eventDispatcher = sessionBuilder.eventDispatcher;
        this.sessionAttributes = sessionBuilder.sessionAttributes;
        this.creationTime = sessionBuilder.creationTime;
        this.status = sessionBuilder.status;
        this.lastReadWriteTime = sessionBuilder.lastReadWriteTime;
        this.isWritable = sessionBuilder.isWritable;
        this.isShuttingDown = sessionBuilder.isShuttingDown;
    }

    @Override
    public Object getId() {
        return id;
    }

    @Override
    public void setId(Object id) {

    }

    @Override
    public Object getAttribute(String key) {
        return sessionAttributes.get(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        sessionAttributes.put(key, value);
    }

    @Override
    public void removeAttribute(String key) {
        sessionAttributes.remove(key);
    }

    @Override
    public void onEvent(Event event) {
        if (!isShuttingDown) {
            eventDispatcher.fireEvent(event);
        }
    }

    @Override
    public EventDispatcher getEventDispatcher() {
        return eventDispatcher;
    }

    @Override
    public void addHandler(EventHandler eventHandler) {
        eventDispatcher.addHandler(eventHandler);
    }

    @Override
    public void removeHandler(EventHandler eventHandler) {
        eventDispatcher.removeHandler(eventHandler);
    }

    @Override
    public List<EventHandler> getEventHandlers(int eventType) {
        return eventDispatcher.getHandlers(eventType);
    }

    @Override
    public long getCreationTime() {
        return creationTime;
    }

    @Override
    public long getLastReadWriteTime() {
        return lastReadWriteTime;
    }

    @Override
    public void setLastReadWriteTime(long lastReadWriteTime) {
        this.lastReadWriteTime = lastReadWriteTime;
    }

    @Override
    public Status getStatus() {
        return status;
    }

    @Override
    public void setStatus(Status status) {
        this.status = status;
    }

    @Override
    public boolean isConnected() {
        return this.status == Status.CONNECTED;
    }

    @Override
    public boolean isWritable() {
        return isWritable;
    }

    @Override
    public void setWritable(boolean isWritable) {
        this.isWritable = isWritable;
    }

    @Override
    public boolean isShuttingDown() {
        return isShuttingDown;
    }

    @Override
    public synchronized void close() {
        isShuttingDown = true;
        EventDispatchers.release(eventDispatcher, this);
        if (sender != null) {
            sender.close();
            sender = null;
        }
        this.status = Status.CLOSED;
    }

    public Map<String, Object> getSessionAttributes() {
        return sessionAttributes;
    }

    @Override
    public MessageSender getSender() {
        return sender;
    }

    @Override
    public void setSender(MessageSender sender) {
        this.sender = sender;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((id == null) ? 0 : id.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        DefaultSession other = (DefaultSession) obj;
        if (id == null) {
            if (other.id != null) {
                return false;
            }
        } else if (!id.equals(other.id)) {
            return false;
        }
        return true;
    }

    /**
     * This class is roughly based on Joshua Bloch's Builder pattern. Since
     * Session class will be extended by child classes, the
     * {@link #validateAndSetValues()} method on this builder is actually called
     * by the {@link DefaultSession} constructor for ease of use. May not be good
     * design though.
     */
    public static class SessionBuilder {
        /**
         * Used to set a unique id on the incoming sessions to this room
         */
        protected static final IdGeneratorService ID_GENERATOR_SERVICE = new IdGeneratorService();
        protected Object id = null;
        protected EventDispatcher eventDispatcher = null;
        protected Map<String, Object> sessionAttributes = null;
        protected long creationTime = 0L;
        protected long lastReadWriteTime = 0L;
        protected Status status = Status.NOT_CONNECTED;
        protected boolean isWritable = true;
        protected volatile boolean isShuttingDown = false;

        public Session build() {
            return new DefaultSession(this);
        }

        /**
         * This method is used to validate and set the variables to default
         * values if they are not already set before calling build. This method
         * is invoked by the constructor of SessionBuilder. <b>Important!</b>
         * Builder child classes which override this method need to call
         * super.validateAndSetValues(), otherwise you could get runtime NPE's.
         */
        protected void validateAndSetValues() {
            if (id == null) {
                id = String.valueOf(ID_GENERATOR_SERVICE.generateFor(DefaultSession.class));
            }
            if (eventDispatcher == null) {
                eventDispatcher = EventDispatchers.sharedDispatcher();
            }
            if (sessionAttributes == null) {
                sessionAttributes = new HashMap<>(4);
            }
            creationTime = System.currentTimeMillis();
        }

        public Object getId() {
            return id;
        }

        public SessionBuilder id(final Object id) {
            this.id = id;
            return this;
        }

        public SessionBuilder eventDispatcher(final EventDispatcher eventDispatcher) {
            this.eventDispatcher = eventDispatcher;
            return this;
        }

        public SessionBuilder sessionAttributes(final Map<String, Object> sessionAttributes) {
            this.sessionAttributes = sessionAttributes;
            return this;
        }

        public SessionBuilder creationTime(long creationTime) {
            this.creationTime = creationTime;
            return this;
        }

        public SessionBuilder lastReadWriteTime(long lastReadWriteTime) {
            this.lastReadWriteTime = lastReadWriteTime;
            return this;
        }

        public SessionBuilder status(Status status) {
            this.status = status;
            return this;
        }

        public SessionBuilder isWritable(boolean isWritable) {
            this.isWritable = isWritable;
            return this;
        }

        public SessionBuilder isShuttingDown(boolean isShuttingDown) {
            this.isShuttingDown = isShuttingDown;
            return this;
        }
    }
}
