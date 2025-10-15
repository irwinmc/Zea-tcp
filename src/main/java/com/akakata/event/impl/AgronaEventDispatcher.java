package com.akakata.event.impl;

import com.akakata.app.Session;
import com.akakata.event.*;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.ManyToOneConcurrentArrayQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * High-performance event dispatcher using Agrona lock-free queue.
 * Replaces JetlangEventDispatcher for better throughput and lower latency.
 *
 * @author Kelvin
 */
public class AgronaEventDispatcher implements EventDispatcher, Agent {

    private static final Logger LOG = LoggerFactory.getLogger(AgronaEventDispatcher.class);
    private static final int QUEUE_CAPACITY = 1 << 15;  // 32K events
    private static final int BATCH_SIZE = 256;

    private final ManyToOneConcurrentArrayQueue<Event> eventQueue;
    private final Map<Integer, List<EventHandler>> handlersByEventType;
    private final List<EventHandler> anyHandlers;
    private final BackoffIdleStrategy idleStrategy;
    private AgentRunner agentRunner;
    private volatile boolean isCloseCalled = false;

    public AgronaEventDispatcher() {
        this.eventQueue = new ManyToOneConcurrentArrayQueue<>(QUEUE_CAPACITY);
        this.handlersByEventType = new HashMap<>();
        this.anyHandlers = new CopyOnWriteArrayList<>();
        this.idleStrategy = new BackoffIdleStrategy(
                1, 10,
                TimeUnit.MICROSECONDS.toNanos(1),
                TimeUnit.MILLISECONDS.toNanos(1)
        );
    }

    /**
     * Initialize and start the dispatcher agent thread.
     * Must be called before using the dispatcher.
     */
    public void initialize() {
        if (agentRunner != null) {
            LOG.warn("Dispatcher already initialized");
            return;
        }

        this.agentRunner = new AgentRunner(
                idleStrategy,
                throwable -> LOG.error("Unhandled exception in event dispatcher", throwable),
                null,
                this
        );

        Thread agentThread = new Thread(agentRunner, "agrona-event-dispatcher");
        agentThread.setDaemon(false);
        agentThread.start();

        LOG.info("AgronaEventDispatcher started with queue capacity {} and batch size {}",
                QUEUE_CAPACITY, BATCH_SIZE);
    }

    @Override
    public int doWork() {
        // Agent interface method - called by AgentRunner in a loop
        int workDone = 0;
        Event event;

        // Process events in batches for better throughput
        for (int i = 0; i < BATCH_SIZE; i++) {
            event = eventQueue.poll();
            if (event == null) {
                break;
            }

            try {
                dispatchEvent(event);
                workDone++;
            } catch (Exception ex) {
                LOG.error("Error dispatching event type {}", event.getType(), ex);
            }
        }

        return workDone;
    }

    @Override
    public String roleName() {
        return "event-dispatcher";
    }

    @Override
    public void fireEvent(Event event) {
        if (!eventQueue.offer(event)) {
            LOG.warn("Event queue full (capacity {}), dropping event type {}",
                    QUEUE_CAPACITY, event.getType());
        }
    }

    private void dispatchEvent(Event event) {
        // Dispatch to ANY handlers (always receive all events)
        for (EventHandler handler : anyHandlers) {
            try {
                handler.onEvent(event);
            } catch (Exception ex) {
                LOG.error("ANY handler error for event type {}", event.getType(), ex);
            }
        }

        // Dispatch to type-specific handlers
        List<EventHandler> handlers = handlersByEventType.get(event.getType());
        if (handlers != null) {
            for (EventHandler handler : handlers) {
                try {
                    handler.onEvent(event);
                } catch (Exception ex) {
                    LOG.error("Handler error for event type {}", event.getType(), ex);
                }
            }
        }
    }

    @Override
    public synchronized void addHandler(EventHandler eventHandler) {
        int eventType = eventHandler.getEventType();
        if (eventType == Events.ANY) {
            addANYHandler(eventHandler);
        } else {
            List<EventHandler> handlers = handlersByEventType.computeIfAbsent(
                    eventType, k -> new CopyOnWriteArrayList<>());
            handlers.add(eventHandler);
            LOG.debug("Added handler for event type {}", eventType);
        }
    }

    protected void addANYHandler(EventHandler eventHandler) {
        int eventType = eventHandler.getEventType();
        if (eventType != Events.ANY) {
            LOG.error("The incoming handler {} is not of type ANY", eventHandler);
            throw new IllegalArgumentException("The incoming handler is not of type ANY");
        }
        anyHandlers.add(eventHandler);
        LOG.debug("Added ANY event handler");
    }

    @Override
    public synchronized List<EventHandler> getHandlers(int eventType) {
        if (eventType == Events.ANY) {
            return new ArrayList<>(anyHandlers);
        }
        List<EventHandler> handlers = handlersByEventType.get(eventType);
        return handlers != null ? new ArrayList<>(handlers) : Collections.emptyList();
    }

    @Override
    public synchronized void removeHandler(EventHandler eventHandler) {
        int eventType = eventHandler.getEventType();
        if (eventType == Events.ANY) {
            anyHandlers.remove(eventHandler);
        } else {
            List<EventHandler> handlers = handlersByEventType.get(eventType);
            if (handlers != null) {
                handlers.remove(eventHandler);
            }
        }
    }

    @Override
    public synchronized void removeHandlersForEvent(int eventType) {
        if (eventType == Events.ANY) {
            int count = anyHandlers.size();
            anyHandlers.clear();
            LOG.debug("Removed {} ANY handlers", count);
        } else {
            List<EventHandler> handlers = handlersByEventType.remove(eventType);
            if (handlers != null) {
                LOG.debug("Removed {} handlers for event type {}", handlers.size(), eventType);
            }
        }
    }

    @Override
    public synchronized boolean removeHandlersForSession(Session session) {
        LOG.debug("Entered removeHandlersForSession for session {}", session);
        List<EventHandler> removeList = new ArrayList<>();

        // Collect handlers to remove from type-specific handlers
        Collection<List<EventHandler>> eventHandlersList = new ArrayList<>(handlersByEventType.values());
        eventHandlersList.add(anyHandlers);

        for (List<EventHandler> handlerList : eventHandlersList) {
            removeList.addAll(getHandlersToRemoveForSession(handlerList, session));
        }

        LOG.debug("Going to remove {} handlers for session: {}", removeList.size(), session);
        for (EventHandler handler : removeList) {
            removeHandler(handler);
        }
        return removeList.size() > 0;
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
    public synchronized void clear() {
        LOG.trace("Going to clear handlers on dispatcher {}", this);
        if (handlersByEventType != null) {
            handlersByEventType.clear();
        }
        if (anyHandlers != null) {
            anyHandlers.clear();
        }
        eventQueue.clear();
    }

    @Override
    public synchronized void close() {
        if (!isCloseCalled) {
            LOG.info("Closing AgronaEventDispatcher");
            if (agentRunner != null) {
                agentRunner.close();
            }
            clear();
            isCloseCalled = true;
        }
    }

    /**
     * Get current queue size (for monitoring).
     *
     * @return number of pending events
     */
    public int getQueueSize() {
        return eventQueue.size();
    }

    /**
     * Get queue capacity.
     *
     * @return maximum queue capacity
     */
    public int getQueueCapacity() {
        return QUEUE_CAPACITY;
    }
}
