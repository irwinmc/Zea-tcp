package com.akakata.event.impl;

import com.akakata.app.DeliveryReport;
import com.akakata.app.Game;
import com.akakata.app.PlayerSession;
import com.akakata.app.Session;
import com.akakata.app.impl.DefaultGame;
import com.akakata.app.impl.DefaultPlayerSession;
import com.akakata.communication.MessageSender;
import com.akakata.communication.Replys;
import com.akakata.communication.impl.RequestMessage;
import com.akakata.event.Event;
import com.akakata.event.EventDispatcher;
import com.akakata.event.Events;
import com.akakata.handlers.DefaultToServerHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static com.akakata.event.impl.DispatcherTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises ordered recipient selection, queue rejection and network read recovery.
 */
@Timeout(10)
class BackpressureAndBroadcastRegressionTest {

    /**
     * Creates the regression fixture.
     */
    BackpressureAndBroadcastRegressionTest() {
    }

    /**
     * Reports accepted submissions through the original API names while preserving each queue's order.
     *
     * @throws Exception if a business barrier cannot complete
     */
    @Test
    void originalSubmissionMethodsReturnAcceptanceAndPreserveQueueOrder() throws Exception {
        try (PausedDispatcher paused = new PausedDispatcher(16); DefaultGame game = new DefaultGame()) {
            PlayerSession player = player(game, paused.dispatcher, new CopyOnWriteArrayList<>());
            Session sessionApi = player;
            Game gameApi = game;
            EventDispatcher dispatcherApi = paused.dispatcher;
            List<Object> sessionEvents = new CopyOnWriteArrayList<>();
            List<Object> gameEvents = new CopyOnWriteArrayList<>();
            dispatcherApi.addHandler(handler(9_010, event -> sessionEvents.add(event.getSource())));
            gameApi.getEventDispatcher().addHandler(handler(9_010, event -> gameEvents.add(event.getSource())));

            assertTrue(sessionApi.onEvent(Events.event(1, 9_010)));
            assertTrue(dispatcherApi.fireEvent(Events.event(2, 9_010), player));
            assertTrue(dispatcherApi.fireEvent(Events.event(3, 9_010)));
            assertTrue(gameApi.send(Events.event(1, 9_010)));
            assertTrue(player.sendToGame(Events.event(2, 9_010)));

            paused.resume();
            drain(dispatcherApi);
            drain(gameApi.getEventDispatcher());
            assertEquals(List.of(1, 2, 3), sessionEvents);
            assertEquals(List.of(1, 2), gameEvents);
        }
    }

    /**
     * Returns false after terminal closure without retaining a caller-owned native payload.
     *
     * @throws Exception if the paused worker cannot start
     */
    @Test
    void originalSubmissionMethodsRejectClosedOwnersWithoutRetainingPayloads() throws Exception {
        try (PausedDispatcher paused = new PausedDispatcher(16); DefaultGame game = new DefaultGame()) {
            PlayerSession session = player(game, paused.dispatcher, new CopyOnWriteArrayList<>());
            ByteBuf payload = Unpooled.buffer().writeByte(1);
            try {
                Event event = Events.event(payload, 9_010);
                session.close();
                assertFalse(session.onEvent(event));
                assertFalse(paused.dispatcher.fireEvent(event, session));
                game.close();
                assertFalse(game.send(event));
                assertFalse(session.sendToGame(event));
                paused.dispatcher.close();
                assertFalse(paused.dispatcher.fireEvent(event));
                assertEquals(1, payload.refCnt());
            } finally {
                payload.release();
            }
        }
    }

    /**
     * Makes full queues observable without acquiring rejected native references.
     *
     * @throws Exception if the paused worker cannot start
     */
    @Test
    void fullQueueReportsRejectionThroughEveryAdmissionApi() throws Exception {
        try (PausedDispatcher paused = new PausedDispatcher(16); DefaultGame game = new DefaultGame()) {
            PlayerSession session = player(game, paused.dispatcher, new CopyOnWriteArrayList<>());
            fill(paused.dispatcher, 16);
            ByteBuf payload = Unpooled.buffer().writeByte(1);
            try {
                Event event = Events.event(payload, 9_001);
                assertFalse(session.onEvent(event));
                assertFalse(paused.dispatcher.fireEvent(event));
                assertFalse(paused.dispatcher.fireEvent(event, session));
                assertThrows(ExecutionException.class,
                        () -> paused.dispatcher.submit(event, ignored -> fail("Rejected action ran")).get());
                assertEquals(1, payload.refCnt());
                assertEquals(16, paused.dispatcher.getQueueSize());
            } finally {
                payload.release();
            }
        }
    }

    /**
     * Closes an overloaded session when an error reply cannot enter its ordered queue.
     *
     * @throws Exception if the paused worker cannot start
     */
    @Test
    void rejectedErrorReplyClosesItsSessionWithoutDiscardingUnrelatedQueuedWork() throws Exception {
        try (PausedDispatcher paused = new PausedDispatcher(16); DefaultGame game = new DefaultGame()) {
            List<Object> messages = new CopyOnWriteArrayList<>();
            PlayerSession session = player(game, paused.dispatcher, messages);
            fill(paused.dispatcher, 16);
            RequestMessage request = new RequestMessage().id(1);
            Replys.error(session, request, 429);
            assertTrue(session.isShuttingDown());
            assertEquals(Session.Status.CLOSED, session.getStatus());
            assertTrue(messages.isEmpty());
            assertEquals(16, paused.dispatcher.getQueueSize());
        }
    }

    /**
     * Reports saturated Game submissions without retaining rejected native payloads.
     *
     * @throws Exception if the callback gate or send completion fails
     */
    @Test
    void fullGameQueueReportsFailedSendAndDoesNotRetainRejectedPayload() throws Exception {
        DefaultGame game = new DefaultGame(16);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        game.getEventDispatcher().addHandler(handler(9_005, event -> {
            entered.countDown();
            await(release);
        }));
        ByteBuf payload = Unpooled.buffer().writeByte(1);
        try {
            game.send(Events.event(null, 9_005));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            fill(game.getEventDispatcher(), 16);
            assertFalse(game.send(Events.event(payload, 9_006)));
            PlayerSession session = player(game, new NettyEventDispatcher(), new CopyOnWriteArrayList<>());
            assertFalse(session.sendToGame(Events.event(payload, 9_006)));
            assertThrows(ExecutionException.class,
                    () -> game.sendBroadcast(Events.networkEvent(payload)).get(3, TimeUnit.SECONDS));
            assertEquals(1, payload.refCnt());
        } finally {
            release.countDown();
            game.close();
            payload.release();
        }
    }

    /**
     * Includes shared Game pressure when deciding whether a channel may continue reading.
     *
     * @throws Exception if callback or business barriers fail
     */
    @Test
    void gameQueuePressurePausesReadsUntilGameWorkFinishes() throws Exception {
        DefaultGame game = new DefaultGame(16);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        game.getEventDispatcher().addHandler(handler(9_007, event -> {
            entered.countDown();
            await(release);
        }));
        PlayerSession session = player(game, EventDispatchers.sharedDispatcher(), new CopyOnWriteArrayList<>());
        EmbeddedChannel channel = new EmbeddedChannel(new DefaultToServerHandler(session));
        try {
            game.send(Events.event(null, 9_007));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            fill(game.getEventDispatcher(), 12);
            channel.writeInbound(Events.dataInEvent(1));
            assertFalse(channel.config().isAutoRead());
            release.countDown();
            drain(session.getEventDispatcher());
            drain(game.getEventDispatcher());
            channel.runPendingTasks();
            assertTrue(channel.config().isAutoRead());
        } finally {
            release.countDown();
            channel.finishAndReleaseAll();
            game.close();
        }
    }

    /**
     * Pauses and resumes inbound reads when the socket's actual writability changes.
     */
    @Test
    void socketWritabilityPausesAndRestoresReads() {
        try (DefaultGame game = new DefaultGame()) {
            PlayerSession session = player(game, EventDispatchers.sharedDispatcher(), new CopyOnWriteArrayList<>());
            EmbeddedChannel channel = new EmbeddedChannel(new DefaultToServerHandler(session));
            try {
                channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
                channel.runPendingTasks();
                assertFalse(channel.config().isAutoRead());
                channel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
                channel.runPendingTasks();
                assertTrue(channel.config().isAutoRead());
                channel.advanceTimeBy(6, TimeUnit.SECONDS);
                channel.runScheduledPendingTasks();
                assertTrue(channel.isActive(), "Recovery must cancel the old pressure deadline");
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    /**
     * Keeps Game events, selected sends, unicasts and broadcasts in one publication order.
     *
     * @throws Exception if a queue barrier or delivery report fails
     */
    @Test
    void selectedRecipientsAreSnapshottedDeduplicatedAndOrderedWithGameEvents() throws Exception {
        try (DefaultGame game = new DefaultGame(); ShardedEventDispatcher group = new ShardedEventDispatcher(2)) {
            List<Object> first = new CopyOnWriteArrayList<>();
            List<Object> second = new CopyOnWriteArrayList<>();
            List<Object> third = new CopyOnWriteArrayList<>();
            PlayerSession one = player(game, group, first);
            PlayerSession two = player(game, group, second);
            PlayerSession three = player(game, group, third);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            List<Integer> gameOrder = new CopyOnWriteArrayList<>();
            game.getEventDispatcher().addHandler(handler(9_002, event -> {
                gameOrder.add((Integer) event.getSource());
                if ((Integer) event.getSource() == 0) {
                    entered.countDown();
                    await(release);
                }
            }));
            try {
                game.send(Events.event(0, 9_002));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                List<PlayerSession> selected = new ArrayList<>(List.of(one, one, two));
                var selectedSend = game.sendToPlayers(Events.networkEvent(1), selected);
                selectedSend.thenRun(() -> gameOrder.add(1));
                selected.clear();
                selected.add(three);
                var unicast = game.sendToPlayer(Events.networkEvent(2), two);
                unicast.thenRun(() -> gameOrder.add(2));
                var broadcast = game.sendBroadcast(Events.networkEvent(3));
                broadcast.thenRun(() -> gameOrder.add(3));
                game.send(Events.event(4, 9_002));
                assertTrue(first.isEmpty());
                release.countDown();
                assertEquals(new DeliveryReport(2, 0, 0), selectedSend.get(3, TimeUnit.SECONDS));
                assertEquals(new DeliveryReport(1, 0, 0), unicast.get(3, TimeUnit.SECONDS));
                assertEquals(new DeliveryReport(3, 0, 0), broadcast.get(3, TimeUnit.SECONDS));
                drain(game.getEventDispatcher());
                drain(group.getShardForSession(one));
                drain(group.getShardForSession(two));
                drain(group.getShardForSession(three));
                assertEquals(List.of(1, 3), first);
                assertEquals(List.of(1, 2, 3), second);
                assertEquals(List.of(3), third);
                assertEquals(List.of(0, 1, 2, 3, 4), gameOrder);
            } finally {
                release.countDown();
            }
        }
    }

    /**
     * Reports a rejected recipient while keeping another queue and its player usable.
     *
     * @throws Exception if a queue barrier or delivery report fails
     */
    @Test
    void overloadedRecipientClosesWithoutSilentlyLosingUpdatesForHealthyPlayers() throws Exception {
        try (PausedDispatcher paused = new PausedDispatcher(16); DefaultGame game = new DefaultGame()) {
            List<Object> healthyMessages = new CopyOnWriteArrayList<>();
            PlayerSession overloaded = player(game, paused.dispatcher, new CopyOnWriteArrayList<>());
            PlayerSession healthy = player(game, EventDispatchers.sharedDispatcher(), healthyMessages);
            fill(paused.dispatcher, 16);
            assertEquals(new DeliveryReport(1, 1, 0),
                    game.sendBroadcast(Events.networkEvent(7)).get(3, TimeUnit.SECONDS));
            drain(healthy.getEventDispatcher());
            assertTrue(overloaded.isShuttingDown());
            assertFalse(healthy.isShuttingDown());
            assertEquals(List.of(7), healthyMessages);
        }
    }

    /**
     * Rejects foreign recipients and skips a selected player that is no longer connected.
     *
     * @throws Exception if the recipient report fails
     */
    @Test
    void foreignPlayersAreRejectedAndDisconnectedSelectionsAreReported() throws Exception {
        try (DefaultGame game = new DefaultGame(); DefaultGame other = new DefaultGame()) {
            PlayerSession disconnected = game.createPlayerSession();
            PlayerSession foreign = other.createPlayerSession();
            try {
                assertThrows(IllegalArgumentException.class,
                        () -> game.sendToPlayer(Events.networkEvent(1), foreign));
                assertEquals(new DeliveryReport(0, 0, 1),
                        game.sendToPlayer(Events.networkEvent(1), disconnected).get(3, TimeUnit.SECONDS));
            } finally {
                disconnected.close();
                foreign.close();
            }
        }
    }

    /**
     * Completes queued send futures and releases their native references on Game closure.
     *
     * @param unicast whether to queue one explicit recipient instead of broadcasting
     * @throws Exception if callback or closure completion fails
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closingGameCompletesPendingSendFuturesAndReleasesPayloads(boolean unicast) throws Exception {
        DefaultGame game = new DefaultGame();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        game.getEventDispatcher().addHandler(handler(9_003, event -> {
            entered.countDown();
            await(release);
        }));
        ByteBuf payload = Unpooled.buffer().writeByte(1);
        CompletableFuture<Void> closed = new CompletableFuture<>();
        try {
            PlayerSession recipient = player(game, new NettyEventDispatcher(), new CopyOnWriteArrayList<>());
            game.send(Events.event(null, 9_003));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var send = unicast ? game.sendToPlayer(Events.networkEvent(payload), recipient)
                    : game.sendBroadcast(Events.networkEvent(payload));
            payload.release();
            Thread.startVirtualThread(() -> {
                try {
                    game.close();
                    closed.complete(null);
                } catch (Throwable failure) {
                    closed.completeExceptionally(failure);
                }
            });
            assertThrows(ExecutionException.class, () -> send.get(3, TimeUnit.SECONDS));
            assertEquals(0, payload.refCnt());
        } finally {
            release.countDown();
            game.close();
            closed.get(3, TimeUnit.SECONDS);
        }
    }

    /**
     * Pauses high-pressure input and restores reads after both business queues drain.
     *
     * @throws Exception if the worker or a business barrier fails
     */
    @Test
    void highQueuePressurePausesAndRecoversReadsWithoutReorderingInput() throws Exception {
        try (PausedDispatcher paused = new PausedDispatcher(16); DefaultGame game = new DefaultGame()) {
            PlayerSession session = player(game, paused.dispatcher, new CopyOnWriteArrayList<>());
            List<Object> input = new CopyOnWriteArrayList<>();
            game.getEventDispatcher().addHandler(handler(Events.SESSION_MESSAGE, e -> input.add(e.getSource())));
            EmbeddedChannel channel = new EmbeddedChannel(new DefaultToServerHandler(session));
            try {
                fill(paused.dispatcher, 11);
                channel.writeInbound(Events.dataInEvent(1));
                channel.writeInbound(Events.dataInEvent(2));
                assertFalse(channel.config().isAutoRead());
                paused.resume();
                drain(paused.dispatcher);
                drain(game.getEventDispatcher());
                channel.runPendingTasks();
                assertTrue(channel.config().isAutoRead());
                assertTrue(channel.isActive());
                assertEquals(List.of(1, 2), input);
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    /**
     * Releases a rejected inbound payload and closes a channel whose queue is already full.
     *
     * @throws Exception if the paused worker cannot start
     */
    @Test
    void fullInboundQueueClosesConnectionAndReleasesNativeInput() throws Exception {
        try (PausedDispatcher paused = new PausedDispatcher(16); DefaultGame game = new DefaultGame()) {
            PlayerSession session = player(game, paused.dispatcher, new CopyOnWriteArrayList<>());
            EmbeddedChannel channel = new EmbeddedChannel(new DefaultToServerHandler(session));
            ByteBuf payload = Unpooled.buffer().writeByte(1);
            try {
                fill(paused.dispatcher, 16);
                channel.writeInbound(Events.dataInEvent(payload));
                assertFalse(channel.isOpen());
                assertTrue(session.isShuttingDown());
                assertEquals(0, payload.refCnt());
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    /**
     * Bounds the duration of a pause and cancels the paused session's retained input.
     *
     * @throws Exception if the paused worker cannot start
     */
    @Test
    void sustainedPressureDeadlineClosesSessionAndReleasesQueuedInput() throws Exception {
        try (PausedDispatcher paused = new PausedDispatcher(16); DefaultGame game = new DefaultGame()) {
            PlayerSession session = player(game, paused.dispatcher, new CopyOnWriteArrayList<>());
            EmbeddedChannel channel = new EmbeddedChannel(new DefaultToServerHandler(session));
            ByteBuf payload = Unpooled.buffer().writeByte(1);
            try {
                fill(paused.dispatcher, 11);
                channel.writeInbound(Events.dataInEvent(payload));
                assertFalse(channel.config().isAutoRead());
                assertEquals(1, payload.refCnt());
                channel.advanceTimeBy(6, TimeUnit.SECONDS);
                channel.runScheduledPendingTasks();
                assertFalse(channel.isOpen());
                assertTrue(session.isShuttingDown());
                assertEquals(0, payload.refCnt());
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    /**
     * Preserves an auto-read flag that the application had disabled before this bridge.
     *
     * @throws Exception if the paused worker cannot start
     */
    @Test
    void removingBridgePreservesAnExternallyDisabledAutoReadFlag() throws Exception {
        try (PausedDispatcher paused = new PausedDispatcher(16); DefaultGame game = new DefaultGame()) {
            PlayerSession session = player(game, paused.dispatcher, new CopyOnWriteArrayList<>());
            DefaultToServerHandler bridge = new DefaultToServerHandler(session);
            EmbeddedChannel channel = new EmbeddedChannel(bridge);
            try {
                channel.config().setAutoRead(false);
                fill(paused.dispatcher, 11);
                channel.writeInbound(Events.dataInEvent(1));
                channel.pipeline().remove(bridge);
                assertFalse(channel.config().isAutoRead());
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    /**
     * Creates one connected player with a recording sender and the selected dispatcher.
     *
     * @param game       the owning Game
     * @param dispatcher the borrowed registration scope
     * @param messages   the captured outbound sources
     * @return the connected player
     */
    private static PlayerSession player(DefaultGame game, EventDispatcher dispatcher, List<Object> messages) {
        var builder = new DefaultPlayerSession.PlayerSessionBuilder().game(game);
        builder.eventDispatcher(dispatcher);
        PlayerSession session = builder.build();
        session.setSender(new MessageSender() {
            @Override
            public Object sendMessage(Object message) {
                messages.add(((Event) message).getSource());
                return null;
            }

            @Override
            public void close() {
            }
        });
        assertTrue(game.connectSession(session));
        game.onLogin(session);
        return session;
    }

    /**
     * Adds unscoped work behind a paused executor without consuming session subscriptions.
     *
     * @param dispatcher the paused queue
     * @param count      the number of tasks to publish
     */
    private static void fill(NettyEventDispatcher dispatcher, int count) {
        for (int i = 0; i < count; i++) {
            assertTrue(dispatcher.fireEvent(Events.event(null, 9_004)));
        }
    }

    /**
     * Waits on a callback gate while preserving an interruption signal.
     *
     * @param gate the release signal
     */
    private static void await(CountDownLatch gate) {
        try {
            assertTrue(gate.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
