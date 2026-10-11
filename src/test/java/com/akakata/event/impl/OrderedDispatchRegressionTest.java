package com.akakata.event.impl;

import com.akakata.app.Session;
import com.akakata.app.impl.DefaultGame;
import com.akakata.app.impl.DefaultPlayerSession;
import com.akakata.app.impl.DefaultSession;
import com.akakata.communication.MessageSender;
import com.akakata.event.Event;
import com.akakata.event.EventHandler;
import com.akakata.event.Events;
import com.akakata.event.SessionEventHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.akakata.event.impl.DispatcherTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies ordered session and application execution, shared ownership and bounded native payloads.
 */
@Timeout(10)
class OrderedDispatchRegressionTest {

    /**
     * Creates this regression fixture.
     */
    OrderedDispatchRegressionTest() {
    }

    /**
     * Keeps a session on one executor across different event types and prevents callback overlap.
     *
     * @throws Exception if the worker barriers fail
     */
    @Test
    void sessionEventsRemainSerialAcrossEventTypes() throws Exception {
        try (ShardedEventDispatcher group = new ShardedEventDispatcher(2)) {
            Session session = session(group, 0);
            List<Integer> observed = new CopyOnWriteArrayList<>();
            Set<Thread> threads = java.util.concurrent.ConcurrentHashMap.newKeySet();
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximum = new AtomicInteger();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            group.addHandler(scoped(session, event -> {
                threads.add(Thread.currentThread());
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                try {
                    if ((Integer) event.getSource() == 0) {
                        entered.countDown();
                        await(release);
                    }
                    observed.add((Integer) event.getSource());
                } finally {
                    active.decrementAndGet();
                }
            }));
            try {
                session.onEvent(Events.event(0, 100));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                for (int i = 1; i < 100; i++) {
                    session.onEvent(Events.event(i, 100 + i % 7));
                }
                assertTrue(observed.isEmpty(), "Later events must wait for the first callback");
            } finally {
                release.countDown();
            }
            drain(group.getShardForSession(session));
            assertEquals(java.util.stream.IntStream.range(0, 100).boxed().toList(), observed);
            assertEquals(1, maximum.get());
            assertEquals(1, threads.size());
            assertFalse(threads.iterator().next().isVirtual());
        }
    }

    /**
     * Preserves each producer's session submission order under concurrent publication.
     *
     * @throws Exception if a producer or completion barrier fails
     */
    @Test
    void concurrentProducersPreserveSessionPublicationOrder() throws Exception {
        try (ShardedEventDispatcher group = new ShardedEventDispatcher(2)) {
            Session session = session(group, 1);
            List<Integer> observed = new CopyOnWriteArrayList<>();
            group.addHandler(scoped(session, event -> observed.add((Integer) event.getSource())));
            CompletableFuture<?>[] producers = new CompletableFuture<?>[4];
            for (int producer = 0; producer < producers.length; producer++) {
                int identity = producer;
                producers[producer] = CompletableFuture.runAsync(() -> {
                    for (int sequence = 0; sequence < 100; sequence++) {
                        int value = identity * 100 + sequence;
                        session.onEvent(Events.event(value, 200 + sequence % 3));
                    }
                });
            }
            CompletableFuture.allOf(producers).get(3, TimeUnit.SECONDS);
            drain(group.getShardForSession(session));
            assertEquals(400, observed.size());
            for (int producer = 0; producer < producers.length; producer++) {
                int identity = producer;
                assertEquals(java.util.stream.IntStream.range(0, 100).boxed().toList(),
                        observed.stream().filter(value -> value / 100 == identity)
                                .map(value -> value % 100).toList());
            }
        }
    }

    /**
     * Queues reentrant submissions behind tasks that have already been published.
     *
     * @throws Exception if the executor barriers fail
     */
    @Test
    void reentrantSubmissionDoesNotJumpAheadOfQueuedEvents() throws Exception {
        try (NettyEventDispatcher dispatcher = new NettyEventDispatcher()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            List<Integer> observed = new CopyOnWriteArrayList<>();
            dispatcher.addHandler(handler(300, event -> {
                int value = (Integer) event.getSource();
                if (value == 0) {
                    entered.countDown();
                    await(release);
                    dispatcher.fireEvent(Events.event(2, 300));
                }
                observed.add(value);
            }));
            try {
                dispatcher.fireEvent(Events.event(0, 300));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                dispatcher.fireEvent(Events.event(1, 300));
            } finally {
                release.countDown();
            }
            // The first barrier may precede the reentrant event; the second cannot.
            drain(dispatcher);
            drain(dispatcher);
            assertEquals(List.of(0, 1, 2), observed);
        }
    }

    /**
     * Removes global and targeted typed subscriptions from every shard where they were installed.
     *
     * @throws Exception if a worker barrier fails
     */
    @Test
    void typedRemovalLeavesNoSubscriptionsOnOtherShards() throws Exception {
        try (ShardedEventDispatcher group = new ShardedEventDispatcher(2)) {
            Session zero = session(group, 0);
            Session one = session(group, 1);
            AtomicInteger calls = new AtomicInteger();
            EventHandler global = handler(1, event -> calls.incrementAndGet());
            SessionEventHandler targeted = new DefaultSessionEventHandler(zero) {
                @Override
                public int getEventType() { return 1; }
                @Override
                public void onEvent(Event event) { calls.incrementAndGet(); }
            };
            group.addHandler(global);
            group.addHandler(targeted);
            assertEquals(2, group.getHandlers(1).size());
            group.removeHandler(global);
            group.removeHandler(targeted);
            zero.onEvent(Events.event(null, 1));
            one.onEvent(Events.event(null, 1));
            drain(group.getShardForSession(zero));
            drain(group.getShardForSession(one));
            assertEquals(0, calls.get());
            assertTrue(group.getHandlers(1).isEmpty());
            group.addHandler(global);
            group.addHandler(targeted);
            group.removeHandlersForEvent(1);
            assertTrue(group.getHandlers(1).isEmpty());
            assertTrue(group.getShardForSession(zero).getHandlers(1).isEmpty());
            assertTrue(group.getShardForSession(one).getHandlers(1).isEmpty());
        }
    }

    /**
     * Routes a targeted event through its session index without querying other sessions' handlers.
     *
     * @throws Exception if the delivery barrier fails
     */
    @Test
    void targetedRoutingDoesNotScanOtherSessionHandlers() throws Exception {
        try (NettyEventDispatcher dispatcher = new NettyEventDispatcher()) {
            AtomicInteger lookups = new AtomicInteger();
            AtomicInteger calls = new AtomicInteger();
            Session target = null;
            for (int i = 0; i < 1000; i++) {
                Session session = session(dispatcher, i);
                if (i == 0) {
                    target = session;
                }
                dispatcher.addHandler(new DefaultSessionEventHandler(session) {
                    @Override
                    public Session getSession() { lookups.incrementAndGet(); return super.getSession(); }
                    @Override
                    public void onEvent(Event event) { calls.incrementAndGet(); }
                });
            }
            lookups.set(0);
            dispatcher.fireEvent(Events.event(null, 400), target);
            drain(dispatcher);
            assertEquals(1, calls.get());
            assertEquals(0, lookups.get(), "Delivery must use captured session registrations");
        }
    }

    /**
     * Keeps application work independent of the shared session executors.
     *
     * @throws Exception if the callback barriers fail
     */
    @Test
    void gameWorkDoesNotBlockSharedSessionShards() throws Exception {
        DefaultGame game = new DefaultGame();
        Session session = new DefaultSession.SessionBuilder().build();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> delivered = new CompletableFuture<>();
        game.getEventDispatcher().addHandler(handler(500, event -> {
            entered.countDown();
            await(release);
        }));
        session.addHandler(scoped(session, event -> delivered.complete(null)));
        try {
            game.send(Events.event(null, 500));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            session.onEvent(Events.event(null, 501));
            delivered.get(3, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            game.close();
            session.close();
        }
    }

    /**
     * Serializes application events of all types on one fixed worker.
     *
     * @throws Exception if the game barrier fails
     */
    @Test
    void gameEventsRemainOrderedAcrossTypes() throws Exception {
        DefaultGame game = new DefaultGame();
        try {
            List<Integer> observed = new CopyOnWriteArrayList<>();
            Set<Thread> threads = java.util.concurrent.ConcurrentHashMap.newKeySet();
            game.getEventDispatcher().addHandler(handler(Events.ANY, event -> {
                observed.add((Integer) event.getSource());
                threads.add(Thread.currentThread());
            }));
            for (int i = 0; i < 200; i++) {
                game.send(Events.event(i, 600 + i % 7));
            }
            barrier(game.getEventDispatcher());
            assertEquals(java.util.stream.IntStream.range(0, 200).boxed().toList(), observed);
            assertEquals(1, threads.size());
        } finally {
            game.close();
        }
    }

    /**
     * Cancels a queued native broadcast when the application closes from its own callback.
     *
     * @throws Exception if callback closure or executor termination fails
     */
    @Test
    void gameCallbackClosureCancelsQueuedBroadcastPayload() throws Exception {
        DefaultGame game = new DefaultGame();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> closed = new CompletableFuture<>();
        ByteBuf payload = Unpooled.buffer().writeByte(1);
        game.getEventDispatcher().addHandler(handler(700, event -> {
            entered.countDown();
            await(release);
            game.close();
            closed.complete(null);
        }));
        try {
            game.send(Events.event(null, 700));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            game.sendBroadcast(Events.networkEvent(payload));
            assertEquals(2, payload.refCnt());
            release.countDown();
            closed.get(3, TimeUnit.SECONDS);
            game.getEventDispatcher().close();
            assertEquals(1, payload.refCnt());
            assertEquals(0, game.getEventDispatcher().getQueueSize());
            assertTrue(game.isShuttingDown());
            assertFalse(game.getEventDispatcher().fireEvent(Events.event(null, 701)));
        } finally {
            release.countDown();
            game.close();
            payload.release();
        }
    }

    /**
     * Keeps external application closers waiting for a running callback while allowing reentry.
     *
     * @throws Exception if a close or callback barrier fails
     */
    @Test
    void concurrentGameClosersWaitForRunningCallback() throws Exception {
        DefaultGame game = new DefaultGame();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        var session = game.createPlayerSession();
        session.setSender(new MessageSender() {
            @Override
            public Object sendMessage(Object message) { return message; }
            @Override
            public void close() { cleanupStarted.countDown(); }
        });
        assertTrue(game.connectSession(session));
        CompletableFuture<Void> callback = new CompletableFuture<>();
        game.getEventDispatcher().addHandler(handler(750, event -> {
            entered.countDown();
            await(release);
            game.close();
            callback.complete(null);
        }));
        try {
            game.send(Events.event(null, 750));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            CompletableFuture<Void> first = CompletableFuture.runAsync(game::close);
            assertTrue(cleanupStarted.await(3, TimeUnit.SECONDS));
            CompletableFuture<Void> second = CompletableFuture.runAsync(() -> {
                secondStarted.countDown();
                game.close();
            });
            assertTrue(secondStarted.await(3, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> second.get(100, TimeUnit.MILLISECONDS));
            assertFalse(first.isDone());
            release.countDown();
            CompletableFuture.allOf(first, second, callback).get(3, TimeUnit.SECONDS);
            assertTrue(game.getSessions().isEmpty());
            assertEquals(0, game.getEventDispatcher().getQueueSize());
        } finally {
            release.countDown();
            game.close();
        }
    }

    /**
     * Releases queued targeted payloads when a session closes without closing its borrowed shard.
     *
     * @throws Exception if the paused executor fails to start
     */
    @Test
    void sessionClosureReleasesOnlyItsQueuedNativePayload() throws Exception {
        try (PausedDispatcher paused = new PausedDispatcher(16)) {
            Session first = session(paused.dispatcher, 1);
            Session second = session(paused.dispatcher, 2);
            ByteBuf payload = Unpooled.buffer().writeByte(1);
            try {
                first.onEvent(Events.event(payload, 760));
                assertEquals(2, payload.refCnt());
                first.close();
                assertEquals(1, payload.refCnt());
                assertEquals(0, paused.dispatcher.getQueueSize());
                assertTrue(paused.dispatcher.fireEvent(Events.event(null, 761), second));
                second.close();
                assertEquals(0, paused.dispatcher.getQueueSize());
            } finally {
                payload.release();
            }
        }
    }

    /**
     * Separates distinct session instances even when custom equality uses the same business ID.
     *
     * @throws Exception if executor startup or a delivery barrier fails
     */
    @Test
    void valueEqualSessionsRemainIsolatedDuringDeliveryAndClosure() throws Exception {
        class EqualSession extends DefaultSession {
            EqualSession(com.akakata.event.EventDispatcher dispatcher) {
                super(new SessionBuilder().id("same-id").eventDispatcher(dispatcher));
            }
            @Override
            public boolean equals(Object other) { return other instanceof EqualSession; }
            @Override
            public int hashCode() { return 1; }
        }
        try (NettyEventDispatcher dispatcher = new NettyEventDispatcher()) {
            Session first = new EqualSession(dispatcher);
            Session second = new EqualSession(dispatcher);
            AtomicInteger firstCalls = new AtomicInteger();
            AtomicInteger secondCalls = new AtomicInteger();
            dispatcher.addHandler(scoped(first, event -> firstCalls.incrementAndGet()));
            dispatcher.addHandler(scoped(second, event -> secondCalls.incrementAndGet()));
            first.onEvent(Events.event(null, 762));
            second.onEvent(Events.event(null, 762));
            drain(dispatcher);
            assertEquals(1, firstCalls.get());
            assertEquals(1, secondCalls.get());
            dispatcher.removeHandlersForSession(first);
            second.onEvent(Events.event(null, 762));
            drain(dispatcher);
            assertEquals(2, secondCalls.get());
        }
        try (PausedDispatcher paused = new PausedDispatcher(16)) {
            Session first = new EqualSession(paused.dispatcher);
            Session second = new EqualSession(paused.dispatcher);
            ByteBuf firstPayload = Unpooled.buffer().writeByte(1);
            ByteBuf secondPayload = Unpooled.buffer().writeByte(2);
            try {
                first.onEvent(Events.event(firstPayload, 763));
                second.onEvent(Events.event(secondPayload, 763));
                first.close();
                assertEquals(1, firstPayload.refCnt());
                assertEquals(2, secondPayload.refCnt());
                assertEquals(1, paused.dispatcher.getQueueSize());
                second.close();
                assertEquals(1, secondPayload.refCnt());
            } finally {
                paused.dispatcher.close();
                firstPayload.release();
                secondPayload.release();
            }
        }
    }

    /**
     * Cancels pending native work immediately while retaining a running callback's native payload.
     *
     * @throws Exception if closure or the running callback fails to finish
     */
    @Test
    void dispatcherClosureWaitsForRunningPayloadAndCancelsQueuedPayload() throws Exception {
        NettyEventDispatcher dispatcher = new NettyEventDispatcher();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ByteBuf running = Unpooled.buffer().writeByte(1);
        ByteBuf queued = Unpooled.buffer().writeByte(2);
        dispatcher.addHandler(handler(770, event -> {
            entered.countDown();
            await(release);
            assertEquals(1, ((ByteBuf) event.getSource()).readByte());
        }));
        try {
            dispatcher.fireEvent(Events.event(running, 770));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            dispatcher.fireEvent(Events.event(queued, 771));
            var closed = dispatcher.beginClose();
            assertFalse(closed.isDone());
            assertEquals(2, running.refCnt());
            assertEquals(1, queued.refCnt());
            release.countDown();
            closed.get(3, TimeUnit.SECONDS);
            assertEquals(1, running.refCnt());
            assertEquals(0, dispatcher.getQueueSize());
        } finally {
            release.countDown();
            dispatcher.close();
            running.release();
            queued.release();
        }
    }

    /**
     * Routes broadcasts through the application worker before each player's ordered outbound queue.
     *
     * @throws Exception if game or player barriers fail
     */
    @Test
    void gameBroadcastsWaitForGameEventsAndRemainOrderedForAllPlayers() throws Exception {
        DefaultGame game = new DefaultGame();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Integer> first = new CopyOnWriteArrayList<>();
        List<Integer> second = new CopyOnWriteArrayList<>();
        var one = player(game, 0, first);
        var two = player(game, 1, second);
        game.getEventDispatcher().addHandler(handler(800, event -> {
            entered.countDown();
            await(release);
        }));
        try {
            game.send(Events.event(null, 800));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            for (int i = 0; i < 100; i++) {
                game.sendBroadcast(Events.networkEvent(i));
            }
            assertTrue(first.isEmpty());
            assertTrue(second.isEmpty());
            release.countDown();
            barrier(game.getEventDispatcher());
            drain(one.getEventDispatcher());
            drain(two.getEventDispatcher());
            List<Integer> expected = java.util.stream.IntStream.range(0, 100).boxed().toList();
            assertEquals(expected, first);
            assertEquals(expected, second);
        } finally {
            release.countDown();
            game.close();
        }
    }

    /**
     * Preserves a shared shard when an individually closed session uses a non-global group.
     *
     * @throws Exception if the surviving session's barrier fails
     */
    @Test
    void sessionClosePreservesBorrowedShardsOfCustomGroups() throws Exception {
        try (ShardedEventDispatcher group = new ShardedEventDispatcher(1)) {
            var shard = group.getShardForSession(session(group, 0));
            Session first = session(shard, 0);
            Session second = session(shard, 1);
            AtomicInteger calls = new AtomicInteger();
            shard.addHandler(scoped(first, event -> fail("Closed session received an event")));
            shard.addHandler(scoped(second, event -> calls.incrementAndGet()));
            first.close();
            second.onEvent(Events.event(null, 900));
            drain(shard);
            assertEquals(1, calls.get());
            second.close();
        }
    }

    /**
     * Shuts down a whole shared group from a callback without joining that callback.
     *
     * @throws Exception if group shutdown fails to return or terminate
     */
    @Test
    void callbackCanCloseItsSharedExecutorGroup() throws Exception {
        ShardedEventDispatcher group = new ShardedEventDispatcher(2);
        NettyEventDispatcher shard = (NettyEventDispatcher) group.getShardForSession(session(group, 0));
        CompletableFuture<Void> callback = new CompletableFuture<>();
        try {
            shard.execute(Events.event(null, 1000), event -> {
                group.close();
                callback.complete(null);
            });
            callback.get(3, TimeUnit.SECONDS);
            group.close();
            assertEquals(0, group.aggregateQueueSize());
            assertThrows(IllegalStateException.class, () -> group.addHandler(handler(1000, event -> {})));
            assertFalse(shard.fireEvent(Events.event(null, 1000)));
        } finally {
            group.close();
        }
    }

    /**
     * Builds a session using a supplied dispatcher and stable identifier.
     *
     * @param dispatcher the session's execution scope
     * @param id the immutable identifier
     * @return the new session
     */
    private static Session session(com.akakata.event.EventDispatcher dispatcher, Object id) {
        return new DefaultSession.SessionBuilder().id(id).eventDispatcher(dispatcher).build();
    }

    /**
     * Creates a wildcard handler with immutable session association.
     *
     * @param session the target session
     * @param action the test callback
     * @return the session-associated handler
     */
    private static SessionEventHandler scoped(Session session, java.util.function.Consumer<Event> action) {
        return new DefaultSessionEventHandler(session) {
            @Override
            public void onEvent(Event event) { action.accept(event); }
        };
    }

    /**
     * Waits for an action barrier without invoking wildcard subscriptions.
     *
     * @param dispatcher the ordered dispatcher
     * @throws Exception if the barrier is rejected or fails
     */
    private static void barrier(NettyEventDispatcher dispatcher) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        assertTrue(dispatcher.execute(Events.event(null, 1_000_002), event -> done.complete(null)));
        done.get(3, TimeUnit.SECONDS);
    }

    /**
     * Waits on a test gate with a bounded failure path for executor cleanup.
     *
     * @param gate the release gate
     */
    private static void await(CountDownLatch gate) {
        try {
            if (!gate.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test gate was not released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /**
     * Connects a player and records its outbound event sources.
     *
     * @param game the application game
     * @param id the immutable session identifier
     * @param messages the thread-safe output recorder
     * @return the connected player
     */
    private static com.akakata.app.PlayerSession player(DefaultGame game, int id, List<Integer> messages) {
        var builder = new DefaultPlayerSession.PlayerSessionBuilder().game(game);
        builder.id(id);
        var session = builder.build();
        session.setSender(new MessageSender() {
            @Override
            public Object sendMessage(Object message) {
                messages.add((Integer) ((Event) message).getSource());
                return message;
            }
            @Override
            public void close() { }
        });
        assertTrue(game.connectSession(session));
        game.onLogin(session);
        return session;
    }
}
