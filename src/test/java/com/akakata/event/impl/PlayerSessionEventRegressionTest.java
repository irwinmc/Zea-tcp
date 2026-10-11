package com.akakata.event.impl;

import com.akakata.app.PlayerSession;
import com.akakata.app.Session;
import com.akakata.app.impl.DefaultGame;
import com.akakata.app.impl.DefaultPlayerSession;
import com.akakata.app.impl.DefaultSession;
import com.akakata.communication.MessageSender;
import com.akakata.event.Event;
import com.akakata.event.EventDispatcher;
import com.akakata.event.EventHandler;
import com.akakata.event.Events;
import com.akakata.event.NetworkEvent;
import com.akakata.event.SessionEventHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies targeted player-event routing and shared-shard ownership.
 */
@Timeout(10)
class PlayerSessionEventRegressionTest {

    /**
     * Creates a player session event regression test instance.
     */
    PlayerSessionEventRegressionTest() {
    }

    /**
     * Game used by this regression fixture.
     */
    private DefaultGame game;

    /**
     * First used by this regression fixture.
     */
    private PlayerSession first;

    /**
     * Second used by this regression fixture.
     */
    private PlayerSession second;

    /**
     * First sender used by this regression fixture.
     */
    private RecordingSender firstSender;

    /**
     * Second sender used by this regression fixture.
     */
    private RecordingSender secondSender;

    /**
     * Sets the up.
     */
    @BeforeEach
    void setUp() {
        game = new DefaultGame();
        firstSender = new RecordingSender();
        secondSender = new RecordingSender();
        // These distinct IDs have identical hashes, regardless of the shard count.
        first = addPlayer("AaAa", firstSender);
        second = addPlayer("BBBB", secondSender);
        assertSame(first.getEventDispatcher(), second.getEventDispatcher());
    }

    /**
     * Releases the resources used by this test fixture.
     */
    @AfterEach
    void tearDown() {
        game.close();
    }

    /**
     * Verifies the regression scenario: unicast only reaches target session.
     *
     * @throws InterruptedException if the operation is interrupted
     */
    @Test
    void unicastOnlyReachesTargetSession() throws InterruptedException {
        NetworkEvent event = Events.networkEvent(Map.of("message", "private"));

        first.onEvent(event);
        drain(first.getEventDispatcher());

        assertEquals(List.of(event), firstSender.messages);
        assertTrue(secondSender.messages.isEmpty(), "Another player must not receive a unicast");
        assertSame(event, firstSender.messages.getFirst());
    }

    /**
     * Verifies the regression scenario: broadcast delivers original event once per player.
     *
     * @throws InterruptedException if the operation is interrupted
     */
    @Test
    void broadcastDeliversOriginalEventOncePerPlayer() throws InterruptedException {
        NetworkEvent event = Events.networkEvent(Map.of("message", "broadcast"));

        game.sendBroadcast(event);
        drain(first.getEventDispatcher());

        assertEquals(List.of(event), firstSender.messages);
        assertEquals(List.of(event), secondSender.messages);
        assertSame(event, firstSender.messages.getFirst());
        assertSame(event, secondSender.messages.getFirst());
    }

    /**
     * Verifies the regression scenario: inbound message broadcasts only once.
     *
     * @throws InterruptedException if the operation is interrupted
     */
    @Test
    void inboundMessageBroadcastsOnlyOnce() throws InterruptedException {
        Map<String, Object> payload = Map.of("message", "from client");

        first.onEvent(Events.dataInEvent(payload));
        // The inbound handler enqueues broadcast deliveries behind the first barrier.
        drain(first.getEventDispatcher());
        drain(first.getEventDispatcher());

        assertEquals(1, firstSender.messages.size());
        assertEquals(1, secondSender.messages.size());
        assertSame(payload, ((NetworkEvent) firstSender.messages.getFirst()).getSource());
        assertSame(payload, ((NetworkEvent) secondSender.messages.getFirst()).getSource());
    }

    /**
     * Verifies the regression scenario: closing session keeps shared shard usable.
     *
     * @throws InterruptedException if the operation is interrupted
     */
    @Test
    void closingSessionKeepsSharedShardUsable() throws InterruptedException {
        EventDispatcher shard = first.getEventDispatcher();

        first.close();

        assertEquals(Session.Status.CLOSED, first.getStatus());
        assertTrue(second.isConnected());
        assertEquals(1, shard.getHandlers(Events.ANY).size(), "The other player's handler must survive");
        NetworkEvent event = Events.networkEvent("still connected");
        second.onEvent(event);
        drain(shard);
        assertEquals(List.of(event), secondSender.messages);

        RecordingSender newcomerSender = new RecordingSender();
        PlayerSession newcomer = addPlayer("AaBB", newcomerSender);
        assertSame(shard, newcomer.getEventDispatcher());
        newcomer.onEvent(event);
        drain(shard);
        assertEquals(List.of(event), newcomerSender.messages);
        assertEquals(List.of(event), secondSender.messages);
    }

    /**
     * Verifies the regression scenario: disconnect event only closes target session.
     *
     * @throws InterruptedException if the operation is interrupted
     */
    @Test
    void disconnectEventOnlyClosesTargetSession() throws InterruptedException {
        EventDispatcher shard = first.getEventDispatcher();

        first.onEvent(Events.event(null, Events.DISCONNECT));
        drain(shard);

        assertEquals(Session.Status.CLOSED, first.getStatus());
        assertTrue(second.isConnected());
        assertFalse(secondSender.closed);
        NetworkEvent event = Events.networkEvent("after peer disconnected");
        second.onEvent(event);
        drain(shard);
        assertEquals(List.of(event), secondSender.messages);
    }

    /**
     * Verifies the regression scenario: shared parent routes base session events by session.
     *
     * @throws InterruptedException if the operation is interrupted
     */
    @Test
    void sharedParentRoutesBaseSessionEventsBySession() throws InterruptedException {
        Session base = new DefaultSession.SessionBuilder().id("AaAa").build();
        RecordingSender sender = new RecordingSender();
        base.setSender(sender);
        base.addHandler(new DefaultSessionEventHandler(base));
        try {
            NetworkEvent event = Events.networkEvent("base session");

            base.onEvent(event);
            // Drain every shard: a type-based routing regression may select a different shard.
            for (int i = 0; i < EventDispatchers.sharedShardCount(); i++) {
                Session probe = new DefaultSession.SessionBuilder().id(i).build();
                drain(EventDispatchers.getShardForSession(probe));
            }

            assertEquals(List.of(event), sender.messages);
            assertTrue(firstSender.messages.isEmpty());
            assertTrue(secondSender.messages.isEmpty());
        } finally {
            base.close();
        }
    }

    /**
     * Verifies the regression scenario: releasing shared parent removes only target handlers.
     *
     * @throws InterruptedException if the operation is interrupted
     */
    @Test
    void releasingSharedParentRemovesOnlyTargetHandlers() throws InterruptedException {
        EventDispatcher shard = first.getEventDispatcher();

        EventDispatchers.release(EventDispatchers.sharedDispatcher(), first);

        assertEquals(1, shard.getHandlers(Events.ANY).size());
        NetworkEvent event = Events.networkEvent("parent release");
        second.onEvent(event);
        drain(shard);
        assertEquals(List.of(event), secondSender.messages);
        assertTrue(firstSender.messages.isEmpty());
    }

    /**
     * Verifies the regression scenario: session routing filters any and typed handlers but keeps
     * global observers.
     *
     * @throws Exception if asynchronous delivery fails
     */
    @Test
    void sessionRoutingFiltersAnyAndTypedHandlersButKeepsGlobalObservers() throws Exception {
        NettyEventDispatcher dispatcher = new NettyEventDispatcher();
        try {
            Session target = new DefaultSession.SessionBuilder().id("target").eventDispatcher(dispatcher).build();
            Session other = new DefaultSession.SessionBuilder().id("other").eventDispatcher(dispatcher).build();
            RecordingHandler targetAny = new RecordingHandler(target, Events.ANY);
            RecordingHandler targetTyped = new RecordingHandler(target, Events.SESSION_MESSAGE);
            RecordingHandler otherAny = new RecordingHandler(other, Events.ANY);
            RecordingHandler otherTyped = new RecordingHandler(other, Events.SESSION_MESSAGE);
            RecordingHandler globalAny = new RecordingHandler(null, Events.ANY);
            RecordingHandler globalTyped = new RecordingHandler(null, Events.SESSION_MESSAGE);
            dispatcher.addHandler(targetAny);
            dispatcher.addHandler(targetTyped);
            dispatcher.addHandler(otherAny);
            dispatcher.addHandler(otherTyped);
            dispatcher.addHandler(globalHandler(globalAny));
            dispatcher.addHandler(globalHandler(globalTyped));
            Event event = Events.dataInEvent("payload");

            target.onEvent(event);
            drain(dispatcher);

            assertEquals(List.of(event), targetAny.events);
            assertEquals(List.of(event), targetTyped.events);
            assertTrue(otherAny.events.isEmpty());
            assertTrue(otherTyped.events.isEmpty());
            assertEquals(List.of(event), globalAny.events);
            assertEquals(List.of(event), globalTyped.events);
            assertSame(event, targetAny.events.getFirst());
        } finally {
            dispatcher.close();
        }
    }

    /**
     * Verifies the regression scenario: unscoped dispatcher events keep broadcast semantics.
     *
     * @throws Exception if the executor barrier fails
     */
    @Test
    void unscopedDispatcherEventsKeepBroadcastSemantics() throws Exception {
        NettyEventDispatcher dispatcher = new NettyEventDispatcher();
        try {
            Session one = new DefaultSession.SessionBuilder().id("one").eventDispatcher(dispatcher).build();
            Session two = new DefaultSession.SessionBuilder().id("two").eventDispatcher(dispatcher).build();
            RecordingHandler firstHandler = new RecordingHandler(one, Events.ANY);
            RecordingHandler secondHandler = new RecordingHandler(two, Events.ANY);
            dispatcher.addHandler(firstHandler);
            dispatcher.addHandler(secondHandler);
            Event event = Events.event(null, Events.START);

            dispatcher.fireEvent(event);
            drain(dispatcher);

            assertEquals(List.of(event), firstHandler.events);
            assertEquals(List.of(event), secondHandler.events);
        } finally {
            dispatcher.close();
        }
    }

    /**
     * Verifies the regression scenario: reusing event across sessions keeps each delivery target.
     *
     * @throws Exception if asynchronous delivery fails
     */
    @Test
    void reusingEventAcrossSessionsKeepsEachDeliveryTarget() throws Exception {
        NettyEventDispatcher dispatcher = new NettyEventDispatcher();
        try {
            Session one = new DefaultSession.SessionBuilder().id("one").eventDispatcher(dispatcher).build();
            Session two = new DefaultSession.SessionBuilder().id("two").eventDispatcher(dispatcher).build();
            RecordingHandler firstHandler = new RecordingHandler(one, Events.ANY);
            RecordingHandler secondHandler = new RecordingHandler(two, Events.ANY);
            dispatcher.addHandler(firstHandler);
            dispatcher.addHandler(secondHandler);
            NetworkEvent event = Events.networkEvent("shared event");

            // Enqueue both deliveries before consumption to catch mutable routing metadata.
            one.onEvent(event);
            two.onEvent(event);
            drain(dispatcher);

            assertEquals(List.of(event), firstHandler.events);
            assertEquals(List.of(event), secondHandler.events);
            assertSame(event, firstHandler.events.getFirst());
            assertSame(event, secondHandler.events.getFirst());
        } finally {
            dispatcher.close();
        }
    }

    /**
     * Verifies the regression scenario: closing dedicated session still closes its dispatcher.
     */
    @Test
    void closingDedicatedSessionStillClosesItsDispatcher() {
        class DedicatedDispatcher extends NettyEventDispatcher {
            private boolean closed;

            @Override
            public synchronized void close() {
                closed = true;
                super.close();
            }
        }
        DedicatedDispatcher dispatcher = new DedicatedDispatcher();
        try {
            Session session = new DefaultSession.SessionBuilder().eventDispatcher(dispatcher).build();

            session.close();

            assertTrue(dispatcher.closed);
        } finally {
            dispatcher.close();
        }
    }

    /**
     * Verifies the regression scenario: add player.
     *
     * @param id the identifier
     * @param sender the outbound sender, possibly null
     * @return the value used by the regression scenario
     */
    private PlayerSession addPlayer(String id, RecordingSender sender) {
        DefaultPlayerSession.PlayerSessionBuilder builder = new DefaultPlayerSession.PlayerSessionBuilder().game(game);
        builder.id(id);
        PlayerSession session = builder.build();
        session.setSender(sender);
        assertTrue(game.connectSession(session));
        game.onLogin(session);
        return session;
    }

    /**
     * Verifies the regression scenario: drain.
     *
     * @param dispatcher the dispatcher
     * @throws InterruptedException if the operation is interrupted
     */
    private void drain(EventDispatcher dispatcher) throws InterruptedException {
        drainScope(dispatcher);
        drainScope(game.getEventDispatcher());
        drainScope(dispatcher);
    }

    /**
     * Waits for deliveries submitted before a barrier on one fixed executor.
     *
     * @param dispatcher the registration scope to drain
     * @throws InterruptedException if the caller is interrupted
     */
    private static void drainScope(EventDispatcher dispatcher) throws InterruptedException {
        DispatcherTestSupport.drain(dispatcher);
    }

    /**
     * Verifies the regression scenario: global handler.
     *
     * @param recorder the recorder
     * @return the value used by the regression scenario
     */
    private static EventHandler globalHandler(RecordingHandler recorder) {
        return new EventHandler() {
            @Override
            public void onEvent(Event event) {
                recorder.onEvent(event);
            }

            @Override
            public int getEventType() {
                return recorder.getEventType();
            }
        };
    }

    /**
     * Records outbound messages and close calls for lifecycle assertions.
     */
    private static final class RecordingSender implements MessageSender {

        /**
         * Creates a recording sender instance.
         */
        private RecordingSender() {
        }

        /**
         * Messages used by this regression fixture.
         */
        private final List<Object> messages = new CopyOnWriteArrayList<>();

        /**
         * Terminal lifecycle flag, published when shutdown begins.
         */
        private volatile boolean closed;

        /**
         * {@inheritDoc}
         */
        @Override
        public Object sendMessage(Object message) {
            messages.add(message);
            return message;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * Records deliveries for player-routing assertions.
     */
    private static final class RecordingHandler implements SessionEventHandler {

        /**
         * Session used by this regression fixture.
         */
        private final Session session;

        /**
         * Event type used by this regression fixture.
         */
        private final int eventType;

        /**
         * Events used by this regression fixture.
         */
        private final List<Event> events = new CopyOnWriteArrayList<>();

        /**
         * Creates a recording handler instance with the supplied dependencies.
         *
         * @param session the session associated with the operation
         * @param eventType the event opcode or subscription selector
         */
        private RecordingHandler(Session session, int eventType) {
            this.session = session;
            this.eventType = eventType;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Session getSession() {
            return session;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void setSession(Session session) {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int getEventType() {
            return eventType;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void onEvent(Event event) {
            events.add(event);
        }
    }
}
