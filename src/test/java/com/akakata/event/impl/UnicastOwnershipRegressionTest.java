package com.akakata.event.impl;

import com.akakata.app.DeliveryReport;
import com.akakata.app.PlayerSession;
import com.akakata.app.impl.DefaultGame;
import com.akakata.app.impl.DefaultPlayerSession;
import com.akakata.communication.OutboundBudget;
import com.akakata.communication.OutboundLimits;
import com.akakata.communication.impl.SocketMessageSender;
import com.akakata.event.Events;
import com.akakata.event.NetworkEvent;
import com.akakata.handlers.codec.SbeEventEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.akakata.event.impl.DispatcherTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies native ownership, FIFO publication and rejection for one selected recipient.
 */
@Timeout(10)
class UnicastOwnershipRegressionTest {

    /**
     * Creates the unicast regression fixture.
     */
    UnicastOwnershipRegressionTest() {
    }

    /**
     * Keeps a caller-released source alive through Game, Session and socket ownership.
     *
     * @param selected whether to select a duplicated single recipient through the collection API
     * @throws Exception if an ordered delivery or barrier fails
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callerReleaseDoesNotInvalidateQueuedSingleRecipientWrites(boolean selected) throws Exception {
        try (DefaultGame game = new DefaultGame(); PausedDispatcher paused = new PausedDispatcher(16)) {
            EmbeddedChannel channel = new EmbeddedChannel(new SbeEventEncoder());
            OutboundBudget budget = new OutboundBudget(4096);
            ByteBuf payload = Unpooled.buffer().writeInt(42);
            try {
                PlayerSession recipient = player(game, paused.dispatcher, channel, budget);
                CompletableFuture<DeliveryReport> result = send(game, recipient, payload, selected);
                payload.release();
                assertEquals(new DeliveryReport(1, 0, 0), result.get(3, TimeUnit.SECONDS));
                assertEquals(1, payload.refCnt());
                paused.resume();
                drain(paused.dispatcher);
                assertEquals(1, payload.refCnt(), "Socket admission must retain the source after Session cleanup");
                channel.runPendingTasks();
                ByteBuf frame = channel.readOutbound();
                try {
                    assertEquals(42, frame.getInt(8));
                } finally {
                    frame.release();
                }
                assertEquals(0, payload.refCnt());
                assertEquals(0, budget.pendingBytes());
            } finally {
                channel.finishAndReleaseAll();
                release(payload);
            }
        }
    }

    /**
     * Returns a queued single-recipient source when the recipient closes before its callback.
     *
     * @param selected whether to use the selected-recipient collection API
     * @throws Exception if an admission report fails
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recipientClosureReclaimsQueuedSourceOwnership(boolean selected) throws Exception {
        try (DefaultGame game = new DefaultGame(); PausedDispatcher paused = new PausedDispatcher(16)) {
            EmbeddedChannel channel = new EmbeddedChannel(new SbeEventEncoder());
            ByteBuf payload = Unpooled.buffer().writeInt(7);
            try {
                PlayerSession recipient = player(game, paused.dispatcher, channel, new OutboundBudget(4096));
                var result = send(game, recipient, payload, selected);
                payload.release();
                assertEquals(new DeliveryReport(1, 0, 0), result.get(3, TimeUnit.SECONDS));
                recipient.close();
                assertEquals(0, payload.refCnt());
                assertEquals(0, paused.dispatcher.getPendingBytes());
            } finally {
                channel.finishAndReleaseAll();
                release(payload);
            }
        }
    }

    /**
     * Closes a saturated recipient without consuming unrelated queued work or leaking a source.
     *
     * @param selected whether to use the selected-recipient collection API
     * @throws Exception if an admission report fails
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fullRecipientQueueReturnsRejectedReportAndReclaimsSource(boolean selected) throws Exception {
        try (DefaultGame game = new DefaultGame(); PausedDispatcher paused = new PausedDispatcher(16)) {
            EmbeddedChannel channel = new EmbeddedChannel(new SbeEventEncoder());
            ByteBuf payload = Unpooled.buffer().writeInt(7);
            try {
                PlayerSession recipient = player(game, paused.dispatcher, channel, new OutboundBudget(4096));
                for (int index = 0; index < 16; index++) {
                    assertTrue(paused.dispatcher.fireEvent(Events.event(null, 9001)));
                }
                var result = send(game, recipient, payload, selected);
                payload.release();
                assertEquals(new DeliveryReport(0, 1, 0), result.get(3, TimeUnit.SECONDS));
                assertTrue(recipient.isShuttingDown());
                assertEquals(16, paused.dispatcher.getQueueSize());
                assertEquals(0, payload.refCnt());
            } finally {
                channel.finishAndReleaseAll();
                release(payload);
            }
        }
    }

    /**
     * Preserves Game publication order before admitting each recipient's native socket write.
     *
     * @throws Exception if the callback gate, send reports or barriers fail
     */
    @Test
    void queuedUnicastsKeepGameAndSocketFifoOrder() throws Exception {
        try (DefaultGame game = new DefaultGame(); NettyEventDispatcher sessionQueue = new NettyEventDispatcher()) {
            EmbeddedChannel channel = new EmbeddedChannel(new SbeEventEncoder());
            OutboundBudget budget = new OutboundBudget(4096);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch proceed = new CountDownLatch(1);
            ByteBuf first = Unpooled.buffer().writeInt(1);
            ByteBuf second = Unpooled.buffer().writeInt(2);
            try {
                PlayerSession recipient = player(game, sessionQueue, channel, budget);
                assertTrue(game.getEventDispatcher().execute(Events.event(null, 9000), ignored -> {
                    entered.countDown();
                    await(proceed);
                }));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var one = send(game, recipient, first, false);
                var two = send(game, recipient, second, true);
                first.release();
                second.release();
                assertFalse(one.isDone());
                assertFalse(two.isDone());
                assertNull(channel.readOutbound());
                proceed.countDown();
                assertEquals(new DeliveryReport(1, 0, 0), one.get(3, TimeUnit.SECONDS));
                assertEquals(new DeliveryReport(1, 0, 0), two.get(3, TimeUnit.SECONDS));
                drain(sessionQueue);
                channel.runPendingTasks();
                for (int expected = 1; expected <= 2; expected++) {
                    ByteBuf frame = channel.readOutbound();
                    try {
                        assertEquals(expected, frame.getInt(8));
                    } finally {
                        frame.release();
                    }
                }
                assertEquals(0, first.refCnt());
                assertEquals(0, second.refCnt());
                assertEquals(0, budget.pendingBytes());
                assertNull(channel.readOutbound());
            } finally {
                proceed.countDown();
                channel.finishAndReleaseAll();
                release(first);
                release(second);
            }
        }
    }

    /**
     * Rejects single-recipient Game admission without borrowing a rejected native source.
     *
     * @throws Exception if the Game callback gate or completion fails
     */
    @Test
    void fullGameQueueDoesNotRetainRejectedUnicastSource() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        try (DefaultGame game = new DefaultGame(16); NettyEventDispatcher sessionQueue = new NettyEventDispatcher()) {
            EmbeddedChannel channel = new EmbeddedChannel(new SbeEventEncoder());
            ByteBuf payload = Unpooled.buffer().writeInt(1);
            try {
                PlayerSession recipient = player(game, sessionQueue, channel, new OutboundBudget(4096));
                assertTrue(game.getEventDispatcher().execute(Events.event(null, 9000), ignored -> {
                    entered.countDown();
                    await(proceed);
                }));
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                for (int index = 0; index < 16; index++) {
                    assertTrue(game.send(Events.event(null, 9001)));
                }
                var result = send(game, recipient, payload, false);
                assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
                assertEquals(1, payload.refCnt());
                assertNull(channel.readOutbound());
            } finally {
                proceed.countDown();
                channel.finishAndReleaseAll();
                release(payload);
            }
        }
    }

    /**
     * Creates a connected Session with a real binary socket sender and independent byte owner.
     *
     * @param game the owning Game
     * @param dispatcher the selected Session queue
     * @param channel the outbound channel
     * @param budget the socket byte owner
     * @return the connected recipient
     */
    private static PlayerSession player(DefaultGame game, NettyEventDispatcher dispatcher,
                                        EmbeddedChannel channel, OutboundBudget budget) {
        var builder = new DefaultPlayerSession.PlayerSessionBuilder().game(game);
        builder.eventDispatcher(dispatcher);
        PlayerSession recipient = builder.build();
        recipient.setSender(new SocketMessageSender(channel, OutboundLimits.DEFAULT, budget));
        assertTrue(game.connectSession(recipient));
        game.onLogin(recipient);
        return recipient;
    }

    /**
     * Publishes one borrowed native source through either single-recipient API shape.
     *
     * @param game the owning Game
     * @param recipient the selected recipient
     * @param payload the caller-owned source
     * @param selected whether to pass a duplicated recipient list
     * @return recipient admission completion
     */
    private static CompletableFuture<DeliveryReport> send(DefaultGame game, PlayerSession recipient,
                                                         ByteBuf payload, boolean selected) {
        NetworkEvent event = Events.networkEvent(payload);
        return selected ? game.sendToPlayers(event, List.of(recipient, recipient)) : game.sendToPlayer(event, recipient);
    }

    /**
     * Waits for a deterministic callback gate with a bounded deadline.
     *
     * @param gate the callback release signal
     */
    private static void await(CountDownLatch gate) {
        try {
            assertTrue(gate.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    /**
     * Releases any remaining caller-owned source after fixture failure.
     *
     * @param buffer the fixture buffer
     */
    private static void release(ByteBuf buffer) {
        if (buffer.refCnt() > 0) {
            buffer.release();
        }
    }
}
