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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Delivers events from a bounded many-producer queue on an Agrona agent.
 *
 * <p>Each queue entry records its event, optional target session and retained native
 * payload. Serial mode invokes handlers on the agent; concurrent mode invokes them
 * on virtual threads and waits up to the configured handler timeout per event.
 *
 * <p>Closure prevents new submissions, waits for active publishers, stops the
 * agent and releases queued payloads. Closure initiated inside an event handler
 * finishes on a separate thread to avoid joining the executing handler or creating
 * a cycle between dispatchers. Already-running handlers can outlive the timeout.
 *
 * @author Kelvin
 * @since 0.7.8
 */
public class AgronaEventDispatcher implements EventDispatcher, Agent {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(AgronaEventDispatcher.class);
    // Shared across dispatchers to avoid both self-joins and cross-dispatcher close cycles.
    /**
     * Per-thread callback marker shared by dispatchers to avoid close join cycles.
     */
    private static final ThreadLocal<Boolean> IN_HANDLER = new ThreadLocal<>();

    /**
     * Maximum number of deliveries held by one event queue.
     */
    private static final int QUEUE_CAPACITY = 1 << 15;

    /**
     * Maximum number of queued deliveries processed per agent work cycle.
     */
    private static final int BATCH_SIZE = 256;

    /**
     * Maximum wait for concurrent handler completion, in milliseconds.
     */
    private static final long HANDLER_TIMEOUT_MS = 5000;

    // ==================== 枚举：执行模式 ====================

    /**
     * Selects how matching handlers for one event are invoked.
     */
    public enum ExecutionMode {
        /**
         * Runs matching handlers sequentially on the dispatcher agent thread.
         */
        SERIAL,

        /**
         * Runs matching handlers on virtual threads and waits up to the handler timeout.
         */
        CONCURRENT
    }

    // ==================== 字段 ====================

    /**
     * Bounded many-producer queue of deliveries and retained payload references.
     */
    private final ManyToOneConcurrentArrayQueue<EventDelivery> eventQueue;

    // Keep routing metadata per delivery so broadcasts can reuse the original event.
    /**
     * Stores routing metadata and the captured payload reference for a single queued delivery.
     *
     * @param event the delivered event
     * @param session the authenticated or targeted session
     * @param payloadReference the captured retained native payload
     */
    private record EventDelivery(Event event, Session session, Object payloadReference) {
    }

    /**
     * Event-type subscriptions stored in concurrent copy-on-write lists.
     */
    private final Map<Integer, List<EventHandler>> handlersByEventType;

    /**
     * Copy-on-write list of wildcard subscriptions.
     */
    private final List<EventHandler> anyHandlers;

    /**
     * Agent backoff strategy used while the queue is idle.
     */
    private final BackoffIdleStrategy idleStrategy;

    /**
     * Handler execution mode selected at construction.
     */
    private final ExecutionMode executionMode;

    /**
     * Runner owning the dispatcher agent thread once initialized.
     */
    private AgentRunner agentRunner;

    /**
     * Terminal flag preventing submissions and registrations after closure starts.
     */
    private volatile boolean isCloseCalled = false;
    /**
     * Count of producers that may still publish or release a delivery.
     */
    private final AtomicInteger activePublishers = new AtomicInteger();
    /**
     * Completion signal for resource cleanup and concurrent close callers.
     */
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();

    // ==================== 构造函数 ====================

    /**
     * Creates a dispatcher queue and handler registry. The agent starts only after initialize().
     *
     * <p>The no-argument overload selects concurrent handler execution.
     */
    public AgronaEventDispatcher() {
        this(ExecutionMode.CONCURRENT);
    }

    /**
     * Creates a dispatcher queue and handler registry. The agent starts only after initialize().
     *
     * <p>The no-argument overload selects concurrent handler execution.
     *
     * @param executionMode the handler execution mode
     */
    public AgronaEventDispatcher(ExecutionMode executionMode) {
        this.executionMode = executionMode;
        this.eventQueue = new ManyToOneConcurrentArrayQueue<>(QUEUE_CAPACITY);
        this.handlersByEventType = new ConcurrentHashMap<>();
        this.anyHandlers = new CopyOnWriteArrayList<>();
        this.idleStrategy = new BackoffIdleStrategy(
                1, 10,
                TimeUnit.MICROSECONDS.toNanos(1),
                TimeUnit.MILLISECONDS.toNanos(1)
        );
    }

    // ==================== 生命周期 ====================

    /**
     * Starts the dispatcher agent once. Repeated initialization of an open dispatcher has no
     * effect.
     *
     * @throws IllegalStateException if the dispatcher is already closed
     */
    public synchronized void initialize() {
        if (isCloseCalled) {
            throw new IllegalStateException("Dispatcher is closed");
        }
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

        LOG.info("AgronaEventDispatcher started with mode={}, queueCapacity={}, batchSize={}", executionMode, QUEUE_CAPACITY, BATCH_SIZE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        boolean firstClose;
        synchronized (this) {
            firstClose = !isCloseCalled;
            if (firstClose) {
                isCloseCalled = true;
            }
        }
        if (!firstClose) {
            if (!Boolean.TRUE.equals(IN_HANDLER.get())) {
                closeCompletion.join();
            }
            return;
        }

        // The runner waits for handlers, so a handler must never join that runner itself.
        if (Boolean.TRUE.equals(IN_HANDLER.get())) {
            Thread.startVirtualThread(this::finishClose);
        } else {
            finishClose();
        }
    }

    /**
     * Waits for publishers, stops the agent, clears handlers and releases queued native references.
     *
     * <p>Completes the close future after cleanup, exceptionally if cleanup fails.
     */
    private void finishClose() {
        try {
            LOG.info("Closing AgronaEventDispatcher");
            while (activePublishers.get() != 0) {
                Thread.onSpinWait();
            }
            if (agentRunner != null) {
                agentRunner.close();
            }
            clear();
            closeCompletion.complete(null);
        } catch (Throwable failure) {
            closeCompletion.completeExceptionally(failure);
            throw failure;
        }
    }

    // ==================== Agent 接口实现 ====================

    /**
     * {@inheritDoc}
     */
    @Override
    public int doWork() {
        int workDone = 0;
        EventDelivery delivery;

        // 批量处理事件，提升吞吐量
        for (int i = 0; i < BATCH_SIZE; i++) {
            if (isCloseCalled) {
                break;
            }
            delivery = eventQueue.poll();
            if (delivery == null) {
                break;
            }

            Event event = delivery.event();
            try {
                dispatchEvent(event, delivery.session());
                workDone++;
            } catch (Exception ex) {
                LOG.error("Error dispatching event type {}", event.getType(), ex);
            } finally {
                EventResources.releasePayload(delivery.payloadReference());
            }
        }

        return workDone;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String roleName() {
        return "event-dispatcher-" + executionMode.name().toLowerCase();
    }

    // ==================== EventDispatcher 接口实现 ====================

    /**
     * {@inheritDoc}
     */
    @Override
    public void fireEvent(Event event) {
        enqueue(event, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void fireEvent(Event event, Session session) {
        enqueue(event, Objects.requireNonNull(session, "session"));
    }

    /**
     * Retains and queues a delivery while open. Releases the retained reference if the queue
     * rejects it.
     *
     * @param event the event to process
     * @param session the session associated with the operation
     */
    private void enqueue(Event event, Session session) {
        Objects.requireNonNull(event, "event");
        if (isCloseCalled) {
            return;
        }
        activePublishers.incrementAndGet();
        try {
            if (!isCloseCalled) {
                Object payloadReference = EventResources.retainPayload(event);
                boolean accepted = false;
                try {
                    accepted = eventQueue.offer(new EventDelivery(event, session, payloadReference));
                    if (!accepted) {
                        LOG.warn("Event queue full (capacity {}), dropping event type {}", QUEUE_CAPACITY, event.getType());
                    }
                } finally {
                    if (!accepted) {
                        EventResources.releasePayload(payloadReference);
                    }
                }
            }
        } finally {
            activePublishers.decrementAndGet();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void addHandler(EventHandler eventHandler) {
        if (isCloseCalled) {
            throw new IllegalStateException("Dispatcher is closed");
        }
        if (eventHandler instanceof SessionEventHandler handler && handler.getSession().isShuttingDown()) {
            return;
        }
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

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized List<EventHandler> getHandlers(int eventType) {
        if (eventType == Events.ANY) {
            return new ArrayList<>(anyHandlers);
        }
        List<EventHandler> handlers = handlersByEventType.get(eventType);
        return handlers != null ? new ArrayList<>(handlers) : Collections.emptyList();
    }

    /**
     * {@inheritDoc}
     */
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

    /**
     * {@inheritDoc}
     */
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

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized boolean removeHandlersForSession(Session session) {
        LOG.debug("Entered removeHandlersForSession for session {}", session);
        List<EventHandler> removeList = new ArrayList<>();

        // 收集所有 handler 列表（包括 anyHandlers 和所有类型的 handlers）
        Collection<List<EventHandler>> eventHandlersList = new ArrayList<>(handlersByEventType.values());
        eventHandlersList.add(anyHandlers);

        // 找出属于该 session 的所有 handler
        for (List<EventHandler> handlerList : eventHandlersList) {
            removeList.addAll(getHandlersToRemoveForSession(handlerList, session));
        }

        LOG.debug("Going to remove {} handlers for session: {}", removeList.size(), session);
        for (EventHandler handler : removeList) {
            removeHandler(handler);
        }
        return !removeList.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void clear() {
        LOG.trace("Going to clear handlers on dispatcher {}", this);
        if (handlersByEventType != null) {
            handlersByEventType.clear();
        }
        if (anyHandlers != null) {
            anyHandlers.clear();
        }
        EventDelivery delivery;
        while ((delivery = eventQueue.poll()) != null) {
            EventResources.releasePayload(delivery.payloadReference());
        }
    }

    // ==================== 内部方法：事件分发（核心优化点）====================

    /**
     * Collects typed and wildcard handlers, filters session handlers by target and invokes the
     * selected execution mode.
     *
     * @param event the event to process
     * @param session the session associated with the operation
     */
    private void dispatchEvent(Event event, Session session) {
        // 收集所有需要处理此事件的 handler

        // 1. 添加匹配会话的 ANY handler，以及通用 handler
        List<EventHandler> handlersToInvoke = new ArrayList<>();
        collectHandlers(anyHandlers, session, handlersToInvoke);

        // 2. 添加特定类型的 handler
        List<EventHandler> typeHandlers = handlersByEventType.get(event.getType());
        if (typeHandlers != null) {
            collectHandlers(typeHandlers, session, handlersToInvoke);
        }

        // 如果没有 handler，直接返回
        if (handlersToInvoke.isEmpty()) {
            return;
        }

        // 根据执行模式选择分发策略
        switch (executionMode) {
            case SERIAL -> dispatchSerial(event, handlersToInvoke);
            case CONCURRENT -> dispatchConcurrent(event, handlersToInvoke);
        }
    }

    /**
     * Adds global handlers and handlers matching the target session to the invocation list.
     *
     * @param handlers the candidate event handlers
     * @param session the session associated with the operation
     * @param result the collection receiving matching handlers
     */
    private void collectHandlers(List<EventHandler> handlers, Session session, List<EventHandler> result) {
        for (EventHandler handler : handlers) {
            if (session == null || !(handler instanceof SessionEventHandler sessionHandler)
                    || session.equals(sessionHandler.getSession())) {
                result.add(handler);
            }
        }
    }

    /**
     * Invokes matching handlers sequentially on the dispatcher agent thread.
     *
     * @param event the event to process
     * @param handlersToInvoke the selected event handlers
     */
    private void dispatchSerial(Event event, List<EventHandler> handlersToInvoke) {
        for (EventHandler handler : handlersToInvoke) {
            try {
                invokeHandler(handler, event);
            } catch (Exception ex) {
                LOG.error("Handler error for event type {} in handler {}", event.getType(), handler.getClass().getSimpleName(), ex);
            }
        }
    }

    /**
     * Invokes matching handlers on virtual threads with a separate retained payload reference for
     * each task.
     *
     * <p>Waits up to the handler timeout without cancelling tasks that continue running.
     *
     * @param event the event to process
     * @param handlersToInvoke the selected event handlers
     */
    private void dispatchConcurrent(Event event, List<EventHandler> handlersToInvoke) {
        var latch = new CountDownLatch(handlersToInvoke.size());

        // 为每个 handler 创建虚拟线程
        for (EventHandler handler : handlersToInvoke) {
            Object payloadReference = EventResources.retainPayload(event);
            try {
                Thread.startVirtualThread(() -> {
                    try {
                        invokeHandler(handler, event);
                    } catch (Exception ex) {
                        LOG.error("Handler error for event type {} in handler {}", event.getType(), handler.getClass().getSimpleName(), ex);
                    } finally {
                        EventResources.releasePayload(payloadReference);
                        latch.countDown();
                    }
                });
            } catch (Throwable failure) {
                EventResources.releasePayload(payloadReference);
                latch.countDown();
                throw failure;
            }
        }

        // 等待所有 handler 完成（带超时）
        try {
            if (!latch.await(HANDLER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                LOG.warn("Handler execution timeout ({} ms) for event type {}, {} handlers still running", HANDLER_TIMEOUT_MS, event.getType(), latch.getCount());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("Handler execution interrupted for event type {}, {} handlers still running", event.getType(), latch.getCount(), e);
        }
    }

    /**
     * Invokes an open handler and records callback context to avoid dispatcher-close join cycles.
     *
     * @param handler the handler or request router
     * @param event the event to process
     */
    private void invokeHandler(EventHandler handler, Event event) {
        if (isCloseCalled || (handler instanceof SessionEventHandler sessionHandler
                && sessionHandler.getSession().isShuttingDown())) {
            return;
        }
        Boolean previous = IN_HANDLER.get();
        IN_HANDLER.set(true);
        try {
            handler.onEvent(event);
        } finally {
            if (previous == null) {
                IN_HANDLER.remove();
            } else {
                IN_HANDLER.set(previous);
            }
        }
    }

    // ==================== 内部方法：Handler 管理 ====================

    /**
     * Registers a wildcard handler in the copy-on-write subscription list.
     *
     * @param eventHandler the handler to register or remove
     */
    protected void addANYHandler(EventHandler eventHandler) {
        int eventType = eventHandler.getEventType();
        if (eventType != Events.ANY) {
            LOG.error("The incoming handler {} is not of type ANY", eventHandler);
            throw new IllegalArgumentException("The incoming handler is not of type ANY");
        }
        anyHandlers.add(eventHandler);
        LOG.debug("Added ANY event handler");
    }

    /**
     * Returns the handlers to remove for session.
     *
     * @param handlerList the handler list
     * @param session the session associated with the operation
     * @return the handlers to remove for session
     */
    protected List<EventHandler> getHandlersToRemoveForSession(List<EventHandler> handlerList, Session session) {
        List<EventHandler> removeList = new ArrayList<>();
        if (handlerList != null) {
            for (EventHandler handler : handlerList) {
                if (handler instanceof SessionEventHandler sessionHandler) {
                    if (sessionHandler.getSession().equals(session)) {
                        removeList.add(handler);
                    }
                }
            }
        }
        return removeList;
    }

    // ==================== 监控方法 ====================

    /**
     * Returns the handler execution mode.
     *
     * @return the handler execution mode
     */
    public ExecutionMode getExecutionMode() {
        return executionMode;
    }

    /**
     * Returns the approximate pending delivery count.
     *
     * @return the approximate pending delivery count
     */
    public int getQueueSize() {
        return eventQueue.size();
    }

    /**
     * Returns the queue capacity.
     *
     * @return the queue capacity
     */
    public int getQueueCapacity() {
        return QUEUE_CAPACITY;
    }

}
