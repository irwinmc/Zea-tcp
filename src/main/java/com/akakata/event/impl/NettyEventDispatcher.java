package com.akakata.event.impl;

import com.akakata.app.Session;
import com.akakata.event.*;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.RejectedExecutionHandlers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Delivers events serially on one fixed Netty business executor.
 *
 * <p>A standalone dispatcher owns a single-thread executor group. Session shards
 * borrow an executor from their owning {@link ShardedEventDispatcher}.
 * Threads start lazily; explicit initialization is unnecessary. Accepted tasks run
 * in submission order, including reentrant submissions, and callbacks never overlap
 * on the same executor. Callbacks must not block or synchronously wait for that executor.
 *
 * <p>Session subscriptions are indexed by object identity and event type. Payloads are
 * borrowed by callbacks; each delivery retains one native reference until completion,
 * rejection or cancellation. Events must not be mutated after submission.
 * Closure cancels queued deliveries and waits for running callbacks when called
 * externally. Closure inside any dispatcher callback returns without waiting to
 * avoid self-joins and cross-dispatcher shutdown cycles.
 *
 * @since 0.8.0
 */
public class NettyEventDispatcher implements EventDispatcher, AutoCloseable {

    /**
     * Default maximum number of pending tasks on each business executor.
     */
    public static final int DEFAULT_QUEUE_CAPACITY = 32_768;

    /**
     * Default retained payload budget per business executor, including running work.
     */
    public static final long DEFAULT_QUEUE_BYTES = 64L * 1024 * 1024;

    /**
     * Maximum admitted payload storage; guarded counters include running callbacks.
     */
    private final long queueBytes;

    /**
     * Admitted payload charge released only after native cleanup.
     */
    private long pendingBytes;

    /**
     * Cumulative admitted, completed and cancelled delivery counts.
     */
    private long acceptedEvents, completedEvents, cancelledEvents;

    /**
     * Logarithmic microsecond buckets for queue wait and callback duration.
     */
    private final long[][] timings = new long[2][64];

    /**
     * Nonblocking subscribers interested in low-water recovery.
     */
    private final Set<Runnable> drainListeners = new LinkedHashSet<>();

    /**
     * Last low-water state used to emit recovery transitions once.
     */
    private boolean drainReady = true;

    /**
     * Logger for failures in application callbacks.
     */
    private static final Logger LOG = LoggerFactory.getLogger(NettyEventDispatcher.class);

    /**
     * Callback marker shared by all dispatcher instances for safe reentrant closure.
     */
    private static final ThreadLocal<Boolean> IN_CALLBACK = new ThreadLocal<>();

    /**
     * Fixed executor providing serial task execution.
     */
    private final EventExecutor executor;

    /**
     * Executor group owned by a standalone dispatcher, or null for a borrowed scope.
     */
    private final DefaultEventExecutorGroup ownedGroup;

    /**
     * Maximum pending task count configured on the underlying executor.
     */
    private final int queueCapacity;

    /**
     * Global subscriptions by event type; guarded by this dispatcher.
     */
    private final Map<Integer, List<Registration>> globalHandlers = new LinkedHashMap<>();

    /**
     * Session subscriptions by stable session identity and event type; guarded by this dispatcher.
     */
    private final Map<Session, Map<Integer, List<Registration>>> sessionHandlers = new IdentityHashMap<>();

    /**
     * Captured registration metadata keyed by handler identity; guarded by this dispatcher.
     */
    private final Map<EventHandler, Registration> registrations = new IdentityHashMap<>();

    /**
     * Accepted tasks, including the running task; guarded by this dispatcher.
     */
    private final Set<Delivery> deliveries = new LinkedHashSet<>();

    /**
     * Completion of scope cleanup and, when owned, executor termination.
     */
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();

    /**
     * Terminal submission and registration flag; guarded by this dispatcher.
     */
    private boolean closed;

    /**
     * Number of queued deliveries, excluding the running callback; guarded by this dispatcher.
     */
    private int queued;

    /**
     * Number of submissions rejected by a full or stopped executor; guarded by this dispatcher.
     */
    private long rejectedEvents;

    /**
     * Creates a standalone dispatcher with the default pending-task capacity.
     */
    public NettyEventDispatcher() {
        this(DEFAULT_QUEUE_CAPACITY);
    }

    /**
     * Creates a standalone dispatcher whose worker starts on the first submission.
     *
     * @param queueCapacity maximum pending tasks, at least 16
     * @throws IllegalArgumentException if the capacity is less than 16
     */
    public NettyEventDispatcher(int queueCapacity) {
        this(queueCapacity, DEFAULT_QUEUE_BYTES);
    }

    /**
     * Creates an ordered executor bounded by task count and retained payload bytes.
     *
     * @param queueCapacity maximum pending tasks, at least 16
     * @param queueBytes positive payload storage budget including running work
     * @throws IllegalArgumentException if either budget is invalid
     */
    public NettyEventDispatcher(int queueCapacity, long queueBytes) {
        if (queueBytes <= 0) throw new IllegalArgumentException("Positive payload budget required");
        this.queueBytes = queueBytes;
        if (queueCapacity < 16) {
            throw new IllegalArgumentException("Queue capacity must be at least 16");
        }
        this.queueCapacity = queueCapacity;
        this.ownedGroup = new DefaultEventExecutorGroup(1,
                new DefaultThreadFactory("zea-event", false), queueCapacity, RejectedExecutionHandlers.reject());
        this.executor = ownedGroup.next();
        ownedGroup.terminationFuture().addListener(future -> {
            if (future.isSuccess()) {
                closeCompletion.complete(null);
            } else {
                closeCompletion.completeExceptionally(future.cause());
            }
        });
    }

    /**
     * Creates a borrowed scope with the default retained-payload budget.
     *
     * @param executor the fixed business executor
     * @param queueCapacity the owner's pending task budget
     */
    NettyEventDispatcher(EventExecutor executor, int queueCapacity) {
        this(executor, queueCapacity, DEFAULT_QUEUE_BYTES);
    }

    /**
     * Creates an isolated registration scope borrowing an ordered executor.
     *
     * @param executor      the fixed business executor
     * @param queueCapacity the owner's maximum pending tasks per executor
     * @param queueBytes the positive payload budget for this scope
     */
    NettyEventDispatcher(EventExecutor executor, int queueCapacity, long queueBytes) {
        this.queueBytes = queueBytes;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.queueCapacity = queueCapacity;
        this.ownedGroup = null;
    }

    /**
     * Validates that this dispatcher is open; workers otherwise start automatically.
     *
     * @throws IllegalStateException if the dispatcher is closed
     */
    public synchronized void initialize() {
        if (closed) {
            throw new IllegalStateException("Dispatcher is closed");
        }
    }

    /**
     * Reports whether this thread is executing a callback in any Netty dispatcher.
     *
     * @return true during a dispatcher callback, including queued actions
     */
    public static boolean isDispatchThread() {
        return Boolean.TRUE.equals(IN_CALLBACK.get());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void addHandler(EventHandler handler) {
        Objects.requireNonNull(handler, "handler");
        initialize();
        Session session = handler instanceof SessionEventHandler scoped ? scoped.getSession() : null;
        if ((session != null && session.isShuttingDown()) || registrations.containsKey(handler)) {
            return;
        }
        Registration registration = new Registration(handler, session, handler.getEventType());
        registrations.put(handler, registration);
        Map<Integer, List<Registration>> index = session == null ? globalHandlers : sessionHandlers.computeIfAbsent(session, ignored -> new LinkedHashMap<>());
        index.computeIfAbsent(registration.type(), ignored -> new ArrayList<>()).add(registration);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized List<EventHandler> getHandlers(int eventType) {
        List<EventHandler> result = new ArrayList<>();
        appendHandlers(globalHandlers.get(eventType), result);
        for (Map<Integer, List<Registration>> index : sessionHandlers.values()) {
            appendHandlers(index.get(eventType), result);
        }
        return result;
    }

    /**
     * Adds one subscription list to a caller-owned snapshot.
     *
     * @param source the indexed registrations, possibly null
     * @param target the snapshot receiving the handlers
     */
    private static void appendHandlers(List<Registration> source, List<EventHandler> target) {
        if (source != null) {
            for (Registration registration : source) {
                target.add(registration.handler());
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void removeHandler(EventHandler handler) {
        Registration registration = registrations.remove(handler);
        if (registration == null) {
            return;
        }
        Map<Integer, List<Registration>> index = registration.session() == null ? globalHandlers : sessionHandlers.get(registration.session());
        List<Registration> entries = index.get(registration.type());
        entries.remove(registration);
        if (entries.isEmpty()) {
            index.remove(registration.type());
        }
        if (registration.session() != null && index.isEmpty()) {
            sessionHandlers.remove(registration.session());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void removeHandlersForEvent(int eventType) {
        for (Registration registration : List.copyOf(registrations.values())) {
            if (registration.type() == eventType) {
                removeHandler(registration.handler());
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean removeHandlersForSession(Session session) {
        List<Delivery> cancelled;
        boolean removed;
        synchronized (this) {
            Map<Integer, List<Registration>> index = sessionHandlers.remove(session);
            removed = index != null;
            if (index != null) {
                for (List<Registration> entries : index.values()) {
                    for (Registration entry : entries) {
                        registrations.remove(entry.handler());
                    }
                }
            }
            cancelled = deliveries.stream().filter(task -> task.session == session).toList();
        }
        cancelled.forEach(Delivery::cancel);
        return removed;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void clear() {
        registrations.clear();
        globalHandlers.clear();
        sessionHandlers.clear();
    }

    /**
     * Attempts an unscoped delivery without blocking the submitting thread.
     *
     * @param event the immutable-after-publication event
     * @return true if accepted, or false when closed or saturated
     */
    @Override
    public boolean fireEvent(Event event) {
        return enqueue(event, null, null);
    }

    /**
     * Attempts a targeted delivery without blocking the submitting thread.
     *
     * @param event   the immutable-after-publication event
     * @param session the target session
     * @return true if accepted, or false when closed, saturated or the session is closing
     */
    @Override
    public boolean fireEvent(Event event, Session session) {
        return enqueue(event, Objects.requireNonNull(session, "session"), null);
    }

    /**
     * Queues an action alongside events while retaining its event payload.
     *
     * <p>The action runs on the same fixed executor and follows the same ordering,
     * rejection and cancellation rules as registered handlers. This supports ordered
     * broadcasts without exposing an internal protocol opcode.
     *
     * @param event  the event whose payload the action borrows
     * @param action the synchronous action to execute
     * @return true if accepted, or false when closed or saturated
     */
    public boolean execute(Event event, Consumer<Event> action) {
        return enqueue(event, null, Objects.requireNonNull(action, "action"));
    }

    /**
     * Submits an ordered action with observable execution, rejection and cancellation.
     *
     * <p>Completion follows payload cleanup. Closing the dispatcher fails queued
     * actions rather than leaving their futures pending. Accepted actions retain
     * their own payload reference. The returned future cannot interrupt a callback.
     *
     * @param event the event borrowed by the action
     * @param action the synchronous action on this executor
     * @return completion of the action, or an exceptional admission or closure result
     */
    public CompletableFuture<Void> submit(Event event, Consumer<Event> action) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        if (!enqueue(event, null, Objects.requireNonNull(action, "action"), completion)) {
            completion.completeExceptionally(new RejectedExecutionException("Dispatcher queue is full or closed"));
        }
        return completion;
    }

    /**
     * Publishes a retained task atomically with respect to dispatcher closure.
     *
     * @param event   the submitted event
     * @param session the target session, or null for unscoped work
     * @param action  the optional queued action
     * @return whether the executor accepted the task
     */
    private boolean enqueue(Event event, Session session, Consumer<Event> action) {
        return enqueue(event, session, action, null);
    }

    /**
     * Publishes a retained task and optional completion under the admission monitor.
     *
     * @param event the submitted event
     * @param session the optional target session
     * @param action the optional queued action
     * @param completion the optional action completion
     * @return whether the executor accepted the task
     */
    private synchronized boolean enqueue(Event event, Session session, Consumer<Event> action,
                                         CompletableFuture<Void> completion) {
        Objects.requireNonNull(event, "event");
        if (closed || (session != null && session.isShuttingDown())) {
            return false;
        }
        long bytes = event.getPayloadSize();
        if (bytes < 0 || bytes > queueBytes - pendingBytes || queued >= queueCapacity) {
            rejectedEvents++;
            return false;
        }
        Delivery delivery = new Delivery(event, session, action, EventResources.retainPayload(event), completion, bytes);
        deliveries.add(delivery);
        queued++;
        pendingBytes += bytes;
        acceptedEvents++;
        drainReady = belowLowWater();
        try {
            executor.execute(delivery);
            return true;
        } catch (RejectedExecutionException rejected) {
            rejectedEvents++;
            acceptedEvents--;
            delivery.admitted = false;
            delivery.cancel();
            return false;
        } catch (Throwable failure) {
            acceptedEvents--;
            delivery.admitted = false;
            delivery.cancel();
            throw failure;
        }
    }

    /**
     * Selects global and target-session registrations without scanning other sessions.
     *
     * @param event   the delivered event
     * @param session the optional target
     * @return the registration snapshot for this delivery
     */
    private synchronized List<Registration> selectHandlers(Event event, Session session) {
        List<Registration> selected = new ArrayList<>();
        collect(globalHandlers, event.getType(), selected);
        if (session != null) {
            collect(sessionHandlers.get(session), event.getType(), selected);
        } else {
            for (Map<Integer, List<Registration>> index : sessionHandlers.values()) {
                collect(index, event.getType(), selected);
            }
        }
        return selected;
    }

    /**
     * Collects wildcard and typed subscriptions from one indexed scope.
     *
     * @param index     the registration index, possibly null
     * @param eventType the delivered opcode
     * @param target    the snapshot receiving matching registrations
     */
    private static void collect(Map<Integer, List<Registration>> index, int eventType, List<Registration> target) {
        if (index == null) {
            return;
        }
        List<Registration> wildcard = index.get((int) Events.ANY);
        if (wildcard != null) {
            target.addAll(wildcard);
        }
        if (eventType != Events.ANY && index.get(eventType) != null) {
            target.addAll(index.get(eventType));
        }
    }

    /**
     * Checks a captured registration immediately before invoking its callback.
     *
     * @param registration the captured subscription
     * @return true if the dispatcher, subscription and associated session remain open
     */
    private synchronized boolean isActive(Registration registration) {
        return !closed && registrations.get(registration.handler()) == registration
                && (registration.session() == null || !registration.session().isShuttingDown());
    }

    /**
     * Begins terminal cleanup without joining an executor thread.
     *
     * @return completion of scope cleanup and any owned executor termination
     */
    CompletableFuture<Void> beginClose() {
        List<Delivery> pending;
        synchronized (this) {
            if (closed) {
                return closeCompletion;
            }
            closed = true;
            clear();
            pending = List.copyOf(deliveries);
        }
        try {
            pending.forEach(Delivery::cancel);
            signalDrain();
        } finally {
            if (ownedGroup != null) {
                ownedGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            } else {
                signalDrain();
                completeBorrowedClose();
            }
        }
        return closeCompletion;
    }

    /**
     * Completes borrowed-scope cleanup outside the registry monitor once no tasks remain.
     */
    private void completeBorrowedClose() {
        boolean complete;
        synchronized (this) {
            complete = closed && ownedGroup == null && deliveries.isEmpty();
        }
        if (complete) {
            closeCompletion.complete(null);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        beginClose();
        if (!isDispatchThread() && !executor.inEventLoop()) {
            closeCompletion.join();
        }
    }

    /**
     * Returns this scope's pending deliveries, excluding its running callback.
     *
     * @return the current pending delivery count
     */
    public synchronized int getQueueSize() {
        return queued;
    }

    /**
     * Returns the executor's pending-task capacity, shared by scopes borrowing that executor.
     *
     * @return maximum pending tasks on the fixed executor
     */
    public int getQueueCapacity() {
        return queueCapacity;
    }

    /**
     * Returns the number of tasks rejected by executor saturation or shutdown.
     *
     * @return cumulative executor rejections, excluding submissions after scope closure
     */
    public synchronized long getRejectedEventCount() {
        return rejectedEvents;
    }

    /**
     * Returns retained payload storage, including the running callback.
     *
     * @return the estimated admitted payload bytes
     */
    public synchronized long getPendingBytes() { return pendingBytes; }

    /**
     * Returns the retained payload admission budget.
     *
     * @return the maximum admitted payload bytes
     */
    public long getQueueBytes() { return queueBytes; }

    /**
     * Subscribes to low-water transitions without polling or changing task ordering.
     *
     * <p>The callback must be nonblocking. It may run on an admitting or business
     * thread. An immediate notification closes the registration-versus-drain race.
     * Closing the returned registration removes it idempotently.
     *
     * @param listener the nonblocking recovery signal
     * @return a removable registration
     */
    public AutoCloseable addDrainListener(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        boolean ready;
        synchronized (this) {
            if (!closed) drainListeners.add(listener);
            ready = belowLowWater() || closed;
        }
        if (ready) listener.run();
        return () -> { synchronized (NettyEventDispatcher.this) { drainListeners.remove(listener); } };
    }

    /**
     * Tests count and byte low-water thresholds under the admission monitor.
     *
     * @return whether both budgets are below half their configured capacity
     */
    private boolean belowLowWater() {
        return queued < queueCapacity / 2 && pendingBytes < Math.max(1, queueBytes / 2);
    }

    /**
     * Emits low-water transitions outside the monitor; listener failures remain isolated.
     */
    private void signalDrain() {
        List<Runnable> listeners = List.of();
        synchronized (this) {
            boolean ready = belowLowWater() || closed;
            if (ready && !drainReady) listeners = List.copyOf(drainListeners);
            drainReady = ready;
            if (closed) drainListeners.clear();
        }
        for (Runnable listener : listeners) {
            try { listener.run(); } catch (RuntimeException failure) { LOG.warn("Drain listener failed", failure); }
        }
    }

    /**
     * Captures executor flow-control counters and bounded duration histograms.
     *
     * @return an immutable metrics snapshot; percentile values are bucket upper bounds
     */
    public synchronized DispatcherSnapshot snapshot() {
        return new DispatcherSnapshot(queued, pendingBytes, queueCapacity, queueBytes,
                acceptedEvents, completedEvents, cancelledEvents, rejectedEvents,
                percentile(timings[0], 95), percentile(timings[0], 99),
                percentile(timings[1], 95), percentile(timings[1], 99));
    }

    /**
     * Finds an approximate percentile using power-of-two microsecond buckets.
     *
     * @param buckets the cumulative timing histogram
     * @param percentile the requested integer percentile
     * @return the bucket upper bound in microseconds, or zero without observations
     */
    private static long percentile(long[] buckets, int percentile) {
        long total = 0;
        for (long count : buckets) total += count;
        if (total == 0) return 0;
        long target = Math.max(1, total - total * (100 - percentile) / 100);
        long count = 0;
        for (int i = 0; i < buckets.length; i++) {
            count += buckets[i];
            if (count >= target) return i >= 62 ? Long.MAX_VALUE : 1L << i;
        }
        return 0;
    }

    /**
     * Records elapsed time in a fixed-size microsecond histogram under the monitor.
     *
     * @param histogram the wait or execution histogram index
     * @param nanos the nonnegative elapsed time
     */
    private void recordTiming(int histogram, long nanos) {
        long micros = Math.max(1, TimeUnit.NANOSECONDS.toMicros(nanos));
        int bucket = 64 - Long.numberOfLeadingZeros(micros - 1);
        timings[histogram][bucket]++;
    }

    /**
     * Returns the executor for owner-side routing and queue sampling.
     *
     * @return the fixed business executor
     */
    EventExecutor executor() {
        return executor;
    }

    /**
     * Identifies scopes that must preserve their owner's shared execution resources.
     *
     * @return true if this scope borrows its business executor
     */
    boolean borrowsExecutor() {
        return ownedGroup == null;
    }

    /**
     * Captures subscription identity so removal remains correct if a handler is later mutated.
     *
     * @param handler the callback
     * @param session the optional associated session
     * @param type    the subscribed opcode
     */
    private record Registration(EventHandler handler, Session session, int type) {
    }

    /**
     * Retains a delivery until it runs or is cancelled, releasing its payload exactly once.
     */
    private final class Delivery implements Runnable {

        /**
         * Queued (0), running (1) or completed/cancelled (2) task state.
         */
        private final AtomicInteger state = new AtomicInteger();

        /**
         * Submitted event, cleared after release to avoid retaining cancelled work in Netty's queue.
         */
        private Event event;

        /**
         * Target session, cleared after completion.
         */
        private Session session;

        /**
         * Optional queued action, cleared after completion.
         */
        private Consumer<Event> action;

        /**
         * Original retained native reference, independent of later event mutation.
         */
        private Object payload;

        /**
         * Optional action completion, resolved after this delivery releases its payload.
         */
        private final CompletableFuture<Void> completion;

        /**
         * Captured payload charge and admission timestamp.
         */
        private final long bytes, submittedNanos = System.nanoTime();

        /**
         * Execution start for callback duration sampling.
         */
        private long startedNanos;

        /**
         * Whether the underlying executor accepted this delivery.
         */
        private boolean admitted = true;

        /**
         * Captures one accepted task and its retained payload.
         *
         * @param event   the event to deliver
         * @param session the optional target session
         * @param action  the optional queued action
         * @param payload the retained native reference
         * @param completion the optional action completion
         * @param bytes the captured payload charge
         */
        private Delivery(Event event, Session session, Consumer<Event> action, Object payload,
                         CompletableFuture<Void> completion, long bytes) {
            this.event = event;
            this.session = session;
            this.action = action;
            this.payload = payload;
            this.completion = completion;
            this.bytes = bytes;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void run() {
            if (!state.compareAndSet(0, 1)) {
                return;
            }
            Boolean previous = IN_CALLBACK.get();
            IN_CALLBACK.set(true);
            Throwable outcome = null;
            try {
                synchronized (NettyEventDispatcher.this) {
                    queued--;
                    startedNanos = System.nanoTime();
                    recordTiming(0, startedNanos - submittedNanos);
                    if (closed || (session != null && session.isShuttingDown())) {
                        outcome = new RejectedExecutionException("Delivery scope closed before execution");
                        return;
                    }
                }
                signalDrain();
                if (action != null) {
                    action.accept(event);
                } else {
                    for (Registration registration : selectHandlers(event, session)) {
                        if (isActive(registration)) {
                            try {
                                registration.handler().onEvent(event);
                            } catch (Exception failure) {
                                LOG.error("Event handler failed for type {}", event.getType(), failure);
                            }
                        }
                    }
                }
            } catch (Throwable failure) {
                outcome = failure;
                LOG.error("Event action failed for type {}", event.getType(), failure);
            } finally {
                try {
                    finish(false);
                } finally {
                    complete(outcome);
                    if (previous == null) {
                        IN_CALLBACK.remove();
                    } else {
                        IN_CALLBACK.set(previous);
                    }
                }
            }
        }

        /**
         * Cancels a queued task without interrupting or releasing a running callback's payload.
         */
        private void cancel() {
            if (state.compareAndSet(0, 2)) {
                try {
                    finish(true);
                } finally {
                    complete(new RejectedExecutionException("Queued delivery was cancelled"));
                }
            }
        }

        /**
         * Resolves an optional action future after native resource cleanup.
         *
         * @param failure the execution or cancellation failure, or null for success
         */
        private void complete(Throwable failure) {
            if (completion != null) {
                if (failure == null) {
                    completion.complete(null);
                } else {
                    completion.completeExceptionally(failure);
                }
            }
        }

        /**
         * Releases the captured payload and removes this task from scope ownership.
         *
         * @param cancelled whether the task was removed before it started
         */
        private void finish(boolean cancelled) {
            try {
                EventResources.releasePayload(payload);
            } finally {
                synchronized (NettyEventDispatcher.this) {
                    event = null;
                    session = null;
                    action = null;
                    payload = null;
                    state.set(2);
                    if (cancelled) {
                        queued--;
                    }
                    deliveries.remove(this);
                    pendingBytes -= bytes;
                    if (admitted) {
                        if (cancelled) cancelledEvents++;
                        else {
                            completedEvents++;
                            recordTiming(1, System.nanoTime() - startedNanos);
                        }
                    }
                }
                signalDrain();
                completeBorrowedClose();
            }
        }
    }
}
