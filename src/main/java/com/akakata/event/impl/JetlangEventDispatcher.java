package com.akakata.event.impl;

import com.akakata.app.Session;
import com.akakata.concurrent.Lane;
import com.akakata.event.*;
import org.jetlang.channels.BatchSubscriber;
import org.jetlang.channels.MemoryChannel;
import org.jetlang.core.Callback;
import org.jetlang.core.Disposable;
import org.jetlang.core.Filter;
import org.jetlang.fibers.Fiber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * @author Kelvin
 */
public class JetlangEventDispatcher implements EventDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(JetlangEventDispatcher.class);
    private final MemoryChannel<Event> eventQueue;
    private final Fiber fiber;
    private final Lane<String, ExecutorService> dispatcherLane;
    private Map<Integer, List<EventHandler>> handlersByEventType;
    private List<EventHandler> anyHandler;
    private volatile boolean isCloseCalled = false;
    /**
     * This Map holds event handlers and their corresponding {@link Disposable}
     * objects. This way, when a handler is removed, the dispose method can be
     * called on the {@link Disposable}.
     */
    private Map<EventHandler, Disposable> disposableHandlerMap;

    public JetlangEventDispatcher(MemoryChannel<Event> eventQueue, Fiber fiber, Lane<String, ExecutorService> lane) {
        this.eventQueue = eventQueue;
        this.fiber = fiber;
        this.dispatcherLane = lane;
    }

    public JetlangEventDispatcher(
            Map<Integer, List<EventHandler>> listenersByEventType,
            List<EventHandler> anyHandler, MemoryChannel<Event> eventQueue,
            Fiber fiber, Lane<String, ExecutorService> lane) {
        super();
        this.handlersByEventType = listenersByEventType;
        this.anyHandler = anyHandler;
        this.eventQueue = eventQueue;
        this.fiber = fiber;
        this.dispatcherLane = lane;
    }

    public void initialize() {
        handlersByEventType = new HashMap<>(4);
        anyHandler = new CopyOnWriteArrayList<>();
        disposableHandlerMap = new HashMap<>(16);
    }

    @Override
    public void fireEvent(final Event event) {
        if (dispatcherLane != null && dispatcherLane.isOnSameLane(Thread.currentThread().getName())) {
            dispatchEventOnSameLane(event);
        } else {
            eventQueue.publish(event);
        }
    }

    @Override
    public void addHandler(final EventHandler eventHandler) {
        final int eventType = eventHandler.getEventType();
        if (eventType == Events.ANY) {
            addANYHandler(eventHandler);
        } else {
            synchronized (this) {
                List<EventHandler> handlers = handlersByEventType.get(eventType);
                if (handlers == null) {
                    handlers = new CopyOnWriteArrayList<>();
                    handlersByEventType.put(eventType, handlers);
                }

                handlers.add(eventHandler);

                Callback<List<Event>> eventCallback = createEventCallbackForHandler(eventHandler);

                // Add the appropriate filter before processing the event.
                Filter<Event> eventFilter = msg -> eventHandler.getEventType() == msg.getType();

                // Create a subscription based on the filter also.
                BatchSubscriber<Event> batchEventSubscriber = new BatchSubscriber<>(
                        fiber, eventCallback, eventFilter, 0, TimeUnit.MILLISECONDS);
                Disposable disposable = eventQueue.subscribe(batchEventSubscriber);
                disposableHandlerMap.put(eventHandler, disposable);
            }
        }
    }

    /**
     * Creates a batch subscription to the jetlang memory channel for the ANY
     * event handler. This method does not require synchronization since we are
     * using CopyOnWriteArrayList
     *
     * @param eventHandler
     */
    protected void addANYHandler(final EventHandler eventHandler) {
        final int eventType = eventHandler.getEventType();
        if (eventType != Events.ANY) {
            LOG.error("The incoming handler {} is not of type ANY", eventHandler);
            throw new IllegalArgumentException("The incoming handler is not of type ANY");
        }
        anyHandler.add(eventHandler);

        Callback<List<Event>> eventCallback = createEventCallbackForHandler(eventHandler);
        BatchSubscriber<Event> batchEventSubscriber = new BatchSubscriber<Event>(
                fiber, eventCallback, 0, TimeUnit.MILLISECONDS);
        Disposable disposable = eventQueue.subscribe(batchEventSubscriber);
        disposableHandlerMap.put(eventHandler, disposable);
    }

    protected Callback<List<Event>> createEventCallbackForHandler(final EventHandler eventHandler) {
        return messages -> {
            for (Event event : messages) {
                eventHandler.onEvent(event);
            }
        };
    }

    protected void dispatchEventOnSameLane(Event event) {
        for (EventHandler handler : anyHandler) {
            handler.onEvent(event);
        }

        // Retrieval is not thread safe, but since we are not setting it to null
        // anywhere is should be fine.
        List<EventHandler> handlers = handlersByEventType.get(event.getType());
        // Iteration is thread safe sine we use copy on write.
        if (handlers != null) {
            for (EventHandler handler : handlers) {
                handler.onEvent(event);
            }
        }
    }

    @Override
    public synchronized List<EventHandler> getHandlers(int eventType) {
        if (eventType == Events.ANY) {
            return anyHandler;
        }
        return handlersByEventType.get(eventType);
    }

    @Override
    public void removeHandler(EventHandler eventHandler) {
        int eventType = eventHandler.getEventType();
        if (eventType == Events.ANY) {
            anyHandler.remove(eventHandler);
        } else {
            synchronized (this) {
                List<EventHandler> handlers = this.handlersByEventType.get(eventType);
                if (handlers != null) {
                    handlers.remove(eventHandler);
                }
            }
        }
        removeDisposableForHandler(eventHandler);
    }

    private synchronized void removeDisposableForHandler(EventHandler eventHandler) {
        Disposable disposable = disposableHandlerMap.get(eventHandler);
        if (disposable != null) {
            disposable.dispose();
            disposableHandlerMap.remove(eventHandler);
        }
    }

    @Override
    public synchronized void removeHandlersForEvent(int eventType) {
        List<EventHandler> handlers;
        if (eventType == Events.ANY) {
            handlers = anyHandler;
        } else {
            handlers = handlersByEventType.get(eventType);
        }
        if (handlers != null) {
            for (EventHandler eventHandler : handlers) {
                removeDisposableForHandler(eventHandler);
            }
            handlers.clear();
        }
        handlersByEventType.put(eventType, null);
    }

    @Override
    public synchronized boolean removeHandlersForSession(Session session) {
        LOG.debug("Entered removeHandlersForSession for session {}", session);
        List<EventHandler> removeList = new ArrayList<EventHandler>();

        Collection<List<EventHandler>> eventHandlersList = new ArrayList<>(handlersByEventType.values());
        eventHandlersList.add(anyHandler);

        for (List<EventHandler> handlerList : eventHandlersList) {
            removeList.addAll(getHandlersToRemoveForSession(handlerList, session));
        }

        LOG.debug("Going to remove {} handlers for session: {}", removeList.size(), session);
        for (EventHandler handler : removeList) {
            removeHandler(handler);
        }
        return (removeList.size() > 0);
    }

    @Override
    public synchronized void clear() {
        LOG.trace("Going to clear handlers on dispatcher {}", this);
        if (handlersByEventType != null) {
            handlersByEventType.clear();
        }
        if (anyHandler != null) {
            anyHandler.clear();
        }
        // Iterate through the list of disposables and dispose each one.
        Collection<Disposable> disposables = disposableHandlerMap.values();
        for (Disposable disposable : disposables) {
            disposable.dispose();
        }
        disposableHandlerMap.clear();
    }

    protected List<EventHandler> getHandlersToRemoveForSession(List<EventHandler> handlerList, Session session) {
        List<EventHandler> removeList = new ArrayList<>();
        if (handlerList != null) {
            for (EventHandler handler : handlerList) {
                if (handler instanceof SessionEventHandler) {
                    SessionEventHandler sessionHandler = (SessionEventHandler) handler;
                    if (sessionHandler.getSession().equals(session)) {
                        removeList.add(handler);
                    }
                }
            }
        }
        return removeList;
    }

    @Override
    public synchronized void close() {
        if (!isCloseCalled) {
            fiber.dispose();
            eventQueue.clearSubscribers();

            // Iterate through the list of disposables and dispose each one.
            Collection<Disposable> disposables = disposableHandlerMap.values();
            for (Disposable disposable : disposables) {
                disposable.dispose();
            }
            handlersByEventType.clear();
            handlersByEventType = null;
            anyHandler.clear();
            anyHandler = null;
            isCloseCalled = true;
        }
    }

    public Map<Integer, List<EventHandler>> getListenersByEventType() {
        return handlersByEventType;
    }

    public void setListenersByEventType(Map<Integer, List<EventHandler>> listenersByEventType) {
        this.handlersByEventType = listenersByEventType;
    }

    public MemoryChannel<Event> getEventQueue() {
        return eventQueue;
    }

    public Fiber getFiber() {
        return fiber;
    }

    public Map<EventHandler, Disposable> getDisposableHandlerMap() {
        return disposableHandlerMap;
    }

    public void setDisposableHandlerMap(Map<EventHandler, Disposable> disposableHandlerMap) {
        this.disposableHandlerMap = disposableHandlerMap;
    }
}
