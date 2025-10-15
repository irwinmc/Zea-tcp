package com.akakata.event.impl;

import com.akakata.app.Session;
import com.akakata.concurrent.NamedThreadFactory;
import com.akakata.event.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * @author Kelvin
 */
public class ExecutorEventDispatcher implements EventDispatcher {

    private static final ExecutorService EXECUTOR;

    static {
        EXECUTOR = new ScheduledThreadPoolExecutor(1, new NamedThreadFactory("event-pool"));
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                EXECUTOR.shutdown();
            }
        });
    }

    private Map<Integer, List<EventHandler>> handlersByEventType;
    private List<EventHandler> genericHandlers;
    private boolean isShuttingDown;

    public ExecutorEventDispatcher() {
        this(new HashMap<>(2), new CopyOnWriteArrayList<>());
    }

    public ExecutorEventDispatcher(
            Map<Integer, List<EventHandler>> handlersByEventType,
            List<EventHandler> genericHandlers) {
        this.handlersByEventType = handlersByEventType;
        this.genericHandlers = genericHandlers;
        this.isShuttingDown = false;
    }

    @Override
    public void addHandler(EventHandler eventListener) {
        int eventType = eventListener.getEventType();
        synchronized (this) {
            if (eventType == Events.ANY) {
                genericHandlers.add(eventListener);
            } else {
                List<EventHandler> handlers = handlersByEventType.get(eventType);
                if (handlers == null) {
                    handlers = new CopyOnWriteArrayList<>();
                    handlersByEventType.put(eventType, handlers);
                }
                handlers.add(eventListener);
            }
        }
    }

    @Override
    public List<EventHandler> getHandlers(int eventType) {
        return handlersByEventType.get(eventType);
    }

    @Override
    public void removeHandler(EventHandler eventListener) {
        int eventType = eventListener.getEventType();
        synchronized (this) {
            if (eventType == Events.ANY) {
                genericHandlers.remove(eventListener);
            } else {
                List<EventHandler> handlers = handlersByEventType.get(eventType);
                if (handlers != null) {
                    handlers.remove(eventListener);
                    // Remove the reference if there are no listeners left.
                    if (handlers.size() == 0) {
                        handlersByEventType.put(eventType, null);
                    }
                }
            }
        }
    }

    @Override
    public void removeHandlersForEvent(int eventType) {
        synchronized (this) {
            List<EventHandler> handlers = handlersByEventType.get(eventType);
            if (handlers != null) {
                handlers.clear();
            }
        }
    }

    @Override
    public boolean removeHandlersForSession(Session session) {
        List<EventHandler> removeList = new ArrayList<>();
        Collection<List<EventHandler>> eventHandlersList = handlersByEventType.values();
        for (List<EventHandler> handlerList : eventHandlersList) {
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
        }
        for (EventHandler handler : removeList) {
            removeHandler(handler);
        }
        return (removeList.size() > 0);
    }

    @Override
    public synchronized void clear() {
        if (handlersByEventType != null) {
            handlersByEventType.clear();
        }
        if (genericHandlers != null) {
            genericHandlers.clear();
        }
    }

    @Override
    public void fireEvent(final Event event) {
        boolean isShuttingDown = false;
        synchronized (this) {
            isShuttingDown = this.isShuttingDown;
        }
        if (!isShuttingDown) {
            EXECUTOR.submit(() -> {
                for (EventHandler handler : genericHandlers) {
                    handler.onEvent(event);
                }

                List<EventHandler> handlers = handlersByEventType.get(event.getType());
                if (handlers != null) {
                    for (EventHandler handler : handlers) {
                        handler.onEvent(event);
                    }
                }
            });
        } else {
            System.err.println("Discarding event: " + event + " as dispatcher is shutting down");
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            isShuttingDown = true;
            genericHandlers.clear();
            handlersByEventType.clear();
        }
    }
}
