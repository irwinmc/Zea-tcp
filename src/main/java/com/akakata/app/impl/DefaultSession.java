package com.akakata.app.impl;

import com.akakata.app.Session;
import com.akakata.communication.MessageSender;
import com.akakata.event.Event;
import com.akakata.event.EventDispatcher;
import com.akakata.event.EventHandler;
import com.akakata.event.impl.EventDispatchers;
import com.akakata.event.impl.NettyEventDispatcher;
import com.akakata.service.IdGeneratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Implements session state, synchronized attributes and coordinated resource cleanup.
 *
 * <p>The identifier is fixed at construction. Lifecycle mutations synchronize on
 * this session; sender and dispatcher cleanup run outside its monitor. Concurrent
 * close callers wait for the initial cleanup, while reentrant close callbacks return.
 * Shared dispatchers retain their other sessions and global handlers.
 *
 * @author Kelvin
 */
public class DefaultSession implements Session {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(DefaultSession.class);

    /**
     * Pending close callbacks, guarded by the session monitor.
     */
    private final List<Runnable> closeListeners = new ArrayList<>();

    /**
     * Completion signal for resource cleanup and concurrent close callers.
     */
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();

    /**
     * Thread initiating session cleanup, used to recognize reentrant close calls.
     */
    private volatile Thread closingThread;

    /**
     * Session id.
     */
    protected final Object id;

    /**
     * Live attribute map wrapped for synchronized individual operations.
     */
    protected final Map<String, Object> sessionAttributes;

    /**
     * Creation time in epoch milliseconds.
     */
    protected final long creationTime;

    /**
     * Dispatcher used for event delivery; ownership depends on whether it is shared.
     */
    protected EventDispatcher eventDispatcher;

    /**
     * Last recorded activity time in epoch milliseconds.
     */
    protected volatile long lastReadWriteTime;

    /**
     * Status.
     */
    protected volatile Status status;

    /**
     * Writable.
     */
    protected volatile boolean isWritable;

    /**
     * Flag indicating that resource shutdown has started.
     */
    protected volatile boolean isShuttingDown;

    /**
     * Current outbound transport sender, detached when closure starts.
     */
    protected volatile MessageSender sender = null;

    /**
     * Validates the builder and initializes a session without copying its attribute map.
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
        this.sessionAttributes = Collections.synchronizedMap(sessionBuilder.sessionAttributes);
        this.creationTime = sessionBuilder.creationTime;
        this.status = sessionBuilder.status;
        this.lastReadWriteTime = sessionBuilder.lastReadWriteTime;
        this.isWritable = sessionBuilder.isWritable;
        this.isShuttingDown = sessionBuilder.isShuttingDown;
    }

    /**
     * Returns the identifier used for session identity and shard selection.
     *
     * @return the session identifier
     */
    @Override
    public Object getId() {
        return id;
    }

    /**
     * Leaves the immutable identifier unchanged; this implementation ignores the argument.
     *
     * @param id the identifier
     */
    @Override
    public void setId(Object id) {

    }

    /**
     * Returns the value stored under an attribute key.
     *
     * @param key the attribute or cache key
     * @return the stored value, or {@code null} if the key is absent or its value is null
     */
    @Override
    public Object getAttribute(String key) {
        return sessionAttributes.get(key);
    }

    /**
     * Stores an attribute value under the supplied key.
     *
     * @param key the attribute or cache key
     * @param value the value to store or encode
     */
    @Override
    public void setAttribute(String key, Object value) {
        sessionAttributes.put(key, value);
    }

    /**
     * Removes the value stored under an attribute key.
     *
     * @param key the attribute or cache key
     */
    @Override
    public void removeAttribute(String key) {
        sessionAttributes.remove(key);
    }

    /**
     * Publishes to the fixed session executor and records accepted activity.
     *
     * <p>Accepted asynchronous deliveries retain their own native payload reference;
     * the caller remains responsible for its original reference.
     *
     * @param event the event to process
     * @return whether the open session's dispatcher accepted the event
     */
    @Override
    public synchronized boolean onEvent(Event event) {
        if (isShuttingDown || !eventDispatcher.fireEvent(event, this)) {
            return false;
        }
        lastReadWriteTime = System.currentTimeMillis();
        return true;
    }

    /**
     * Returns the dispatcher used by this session. It may be shared with other sessions.
     *
     * @return the associated dispatcher
     */
    @Override
    public EventDispatcher getEventDispatcher() {
        return eventDispatcher;
    }

    /**
     * Registers a handler with the session dispatcher. A closed default session ignores
     * registration.
     *
     * @param eventHandler the handler to register or remove
     */
    @Override
    public synchronized void addHandler(EventHandler eventHandler) {
        if (!isShuttingDown) {
            eventDispatcher.addHandler(eventHandler);
        }
    }

    /**
     * Removes a handler from the session dispatcher.
     *
     * @param eventHandler the handler to register or remove
     */
    @Override
    public void removeHandler(EventHandler eventHandler) {
        eventDispatcher.removeHandler(eventHandler);
    }

    /**
     * Returns handlers visible to this session's dispatcher for the supplied event type.
     *
     * @param eventType the event opcode or subscription selector
     * @return the handlers registered for the type; the dispatcher may also serve other sessions
     */
    @Override
    public List<EventHandler> getEventHandlers(int eventType) {
        return eventDispatcher.getHandlers(eventType);
    }

    /**
     * Returns the creation timestamp in epoch milliseconds.
     *
     * @return the creation timestamp in milliseconds
     */
    @Override
    public long getCreationTime() {
        return creationTime;
    }

    /**
     * Returns the last recorded activity timestamp in epoch milliseconds.
     *
     * @return the last recorded activity timestamp in milliseconds
     */
    @Override
    public long getLastReadWriteTime() {
        return lastReadWriteTime;
    }

    /**
     * Updates the recorded activity timestamp in epoch milliseconds.
     *
     * @param lastReadWriteTime the activity timestamp in epoch milliseconds
     */
    @Override
    public void setLastReadWriteTime(long lastReadWriteTime) {
        this.lastReadWriteTime = lastReadWriteTime;
    }

    /**
     * Returns the current connection state.
     *
     * @return the current session status
     */
    @Override
    public Status getStatus() {
        return status;
    }

    /**
     * Updates the connection state. The closed state is terminal in the default implementation.
     *
     * @param status the connection state or HTTP response status
     */
    @Override
    public synchronized void setStatus(Status status) {
        if (!isShuttingDown) {
            this.status = status;
        }
    }

    /**
     * Tests whether the session status is connected.
     *
     * @return {@code true} if the status is {@link Session.Status#CONNECTED}
     */
    @Override
    public boolean isConnected() {
        return this.status == Status.CONNECTED;
    }

    /**
     * Returns the session's outbound-write eligibility flag.
     *
     * @return {@code true} if the session is marked writable
     */
    @Override
    public boolean isWritable() {
        return isWritable;
    }

    /**
     * Updates outbound-write eligibility. A closed default session remains nonwritable.
     *
     * @param isWritable whether outbound writes are initially permitted
     */
    @Override
    public synchronized void setWritable(boolean isWritable) {
        if (!isShuttingDown) {
            this.isWritable = isWritable;
        }
    }

    /**
     * Tests whether session closure has started.
     *
     * @return {@code true} once closure starts
     */
    @Override
    public boolean isShuttingDown() {
        return isShuttingDown;
    }

    /**
     * Marks the session closed and releases its owned resources once.
     *
     * <p>External concurrent callers wait for the initiating cleanup. Reentrant calls
     * from the closing thread or a dispatcher callback return immediately to avoid
     * waiting on the callback being closed. Close listeners run even if dispatcher or sender
     * cleanup throws; listener failures are logged individually.
     */
    @Override
    public void close() {
        MessageSender senderToClose;
        List<Runnable> listeners;
        boolean alreadyClosing;
        Thread owner;
        synchronized (this) {
            alreadyClosing = isShuttingDown;
            owner = closingThread;
            if (alreadyClosing) {
                senderToClose = null;
                listeners = List.of();
            } else {
                closingThread = Thread.currentThread();
                isShuttingDown = true;
                status = Status.CLOSED;
                isWritable = false;
                senderToClose = sender;
                sender = null;
                listeners = new ArrayList<>(closeListeners);
                closeListeners.clear();
            }
        }
        if (alreadyClosing) {
            // Reentrant close callbacks must not wait for themselves.
            if (owner != Thread.currentThread() && owner != null && !NettyEventDispatcher.isDispatchThread()) {
                closeCompletion.join();
            }
            return;
        }

        // Never hold the session monitor while waiting for dispatchers or calling external code.
        try {
            try {
                EventDispatchers.release(eventDispatcher, this);
            } finally {
                if (senderToClose != null) {
                    senderToClose.close();
                }
            }
        } finally {
            try {
                for (Runnable listener : listeners) {
                    try {
                        listener.run();
                    } catch (Exception e) {
                        LOG.error("Session close listener failed for session {}", id, e);
                    }
                }
            } finally {
                closeCompletion.complete(null);
                closingThread = null;
            }
        }
    }

    /**
     * Registers a callback to run once when the session closes.
     *
     * <p>A callback added after closure has started runs immediately on the registering
     * thread. Registered callbacks execute outside the session monitor.
     *
     * @param listener the close callback
     * @throws NullPointerException if the listener is null
     */
    public void addCloseListener(Runnable listener) {
        synchronized (this) {
            if (!isShuttingDown) {
                closeListeners.add(java.util.Objects.requireNonNull(listener, "listener"));
                return;
            }
        }
        listener.run();
    }

    /**
     * Returns the live synchronized attribute map.
     *
     * <p>Iteration and compound operations require synchronization on the returned map.
     *
     * @return the mutable attribute map
     */
    public Map<String, Object> getSessionAttributes() {
        return sessionAttributes;
    }

    /**
     * Returns the current outbound sender.
     *
     * @return the sender, or {@code null} if no sender is attached
     */
    @Override
    public MessageSender getSender() {
        return sender;
    }

    /**
     * Attaches an outbound sender.
     *
     * <p>The default implementation closes a replaced sender outside the session
     * monitor. A sender supplied after closure is closed immediately and is not attached.
     *
     * @param sender the outbound sender, possibly null
     */
    @Override
    public void setSender(MessageSender sender) {
        MessageSender senderToClose;
        synchronized (this) {
            if (isShuttingDown) {
                senderToClose = sender;
            } else {
                senderToClose = this.sender == sender ? null : this.sender;
                this.sender = sender;
            }
        }
        if (senderToClose != null) {
            senderToClose.close();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((id == null) ? 0 : id.hashCode());
        return result;
    }

    /**
     * {@inheritDoc}
     */
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
     * Builds sessions with optional identifiers, dispatcher selection and initial state.
     *
     * <p>Missing identifiers are generated and a missing dispatcher uses the shared
     * dispatcher. Attribute maps are wrapped by the built session rather than copied.
     */
    public static class SessionBuilder {

        /**
         * Creates a builder with the default initial configuration.
         */
        public SessionBuilder() {
        }

        /**
         * Identifier generator used when a builder has no explicit identifier.
         */
        protected static final IdGeneratorService ID_GENERATOR_SERVICE = new IdGeneratorService();

        /**
         * Identifier used for session identity.
         */
        protected Object id = null;

        /**
         * Dispatcher used for event delivery; ownership depends on whether it is shared.
         */
        protected EventDispatcher eventDispatcher = null;

        /**
         * Live attribute map wrapped for synchronized individual operations.
         */
        protected Map<String, Object> sessionAttributes = null;

        /**
         * Creation time in epoch milliseconds.
         */
        protected long creationTime = 0L;

        /**
         * Last recorded activity time in epoch milliseconds.
         */
        protected long lastReadWriteTime = 0L;

        /**
         * Connection state used to initialize or report session lifecycle.
         */
        protected Status status = Status.NOT_CONNECTED;

        /**
         * Flag controlling whether the session is eligible for outbound writes.
         */
        protected boolean isWritable = true;

        /**
         * Flag indicating that resource shutdown has started.
         */
        protected volatile boolean isShuttingDown = false;

        /**
         * Constructs a session using this builder's current values after validation.
         *
         * @return the newly constructed session
         */
        public Session build() {
            return new DefaultSession(this);
        }

        /**
         * Generates an identifier and applies defaults to unset attributes, timestamps and
         * dispatcher selection.
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

        /**
         * Returns the identifier.
         *
         * @return the identifier
         */
        public Object getId() {
            return id;
        }

        /**
         * Sets the stable session identifier.
         *
         * @param id the identifier
         * @return this builder for chaining
         */
        public SessionBuilder id(final Object id) {
            this.id = id;
            return this;
        }

        /**
         * Selects a dispatcher; null selects the shared default during validation.
         *
         * @param eventDispatcher the event dispatcher
         * @return this builder for chaining
         */
        public SessionBuilder eventDispatcher(final EventDispatcher eventDispatcher) {
            this.eventDispatcher = eventDispatcher;
            return this;
        }

        /**
         * Sets the backing attribute map without copying it.
         *
         * @param sessionAttributes the backing attribute map
         * @return this builder for chaining
         */
        public SessionBuilder sessionAttributes(final Map<String, Object> sessionAttributes) {
            this.sessionAttributes = sessionAttributes;
            return this;
        }

        /**
         * Sets the initial epoch-millisecond creation timestamp.
         *
         * @param creationTime the creation timestamp in epoch milliseconds
         * @return this builder for chaining
         */
        public SessionBuilder creationTime(long creationTime) {
            this.creationTime = creationTime;
            return this;
        }

        /**
         * Sets the initial epoch-millisecond activity timestamp.
         *
         * @param lastReadWriteTime the activity timestamp in epoch milliseconds
         * @return this builder for chaining
         */
        public SessionBuilder lastReadWriteTime(long lastReadWriteTime) {
            this.lastReadWriteTime = lastReadWriteTime;
            return this;
        }

        /**
         * Sets the initial session connection state.
         *
         * @param status the connection state or HTTP response status
         * @return this builder for chaining
         */
        public SessionBuilder status(Status status) {
            this.status = status;
            return this;
        }

        /**
         * Sets the initial outbound-write eligibility flag.
         *
         * @param isWritable whether outbound writes are initially permitted
         * @return this builder for chaining
         */
        public SessionBuilder isWritable(boolean isWritable) {
            this.isWritable = isWritable;
            return this;
        }

        /**
         * Sets the initial shutdown flag.
         *
         * @param isShuttingDown whether the session starts with its shutdown flag set
         * @return this builder for chaining
         */
        public SessionBuilder isShuttingDown(boolean isShuttingDown) {
            this.isShuttingDown = isShuttingDown;
            return this;
        }
    }
}
