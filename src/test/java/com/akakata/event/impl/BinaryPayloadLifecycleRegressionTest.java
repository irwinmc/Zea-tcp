package com.akakata.event.impl;

import com.akakata.app.PlayerSession;
import com.akakata.app.impl.DefaultGame;
import com.akakata.communication.impl.NettyMessageBuffer;
import com.akakata.communication.impl.SocketMessageSender;
import com.akakata.event.Event;
import com.akakata.event.EventHandler;
import com.akakata.event.Events;
import com.akakata.handlers.DefaultToServerHandler;
import com.akakata.handlers.codec.MessageBufferEventEncoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies native payload reference ownership across delivery, broadcast, rejection and failed
 * writes.
 */
@Timeout(10)
class BinaryPayloadLifecycleRegressionTest {

    /**
     * Creates a binary payload lifecycle regression test instance.
     */
    BinaryPayloadLifecycleRegressionTest() {
    }

    /**
     * Verifies the regression scenario: broadcast writes hold independent payload references.
     *
     * @param inbound the inbound
     * @throws Exception if the operation fails
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void broadcastWritesHoldIndependentPayloadReferences(boolean inbound) throws Exception {
        DefaultGame game = new DefaultGame();
        EmbeddedChannel firstChannel = new EmbeddedChannel(new MessageBufferEventEncoder());
        EmbeddedChannel secondChannel = new EmbeddedChannel(new MessageBufferEventEncoder());
        ByteBuf payload = Unpooled.buffer().writeInt(1234);
        ByteBuf firstFrame = null;
        ByteBuf secondFrame = null;
        try {
            PlayerSession first = player(game, "AaAa", firstChannel);
            player(game, "BBBB", secondChannel);

            if (inbound) {
                game.getEventDispatcher().addHandler(DispatcherTestSupport.handler(Events.SESSION_MESSAGE,
                        event -> game.sendBroadcast(Events.networkEvent(event.getSource()))));
                firstChannel.pipeline().addLast(new DefaultToServerHandler(first));
                firstChannel.writeInbound(Events.event(new NettyMessageBuffer(payload), Events.SESSION_MESSAGE));
            } else {
                game.sendBroadcast(Events.networkEvent(new NettyMessageBuffer(payload)));
            }
            drain(first);
            drain(first);
            firstChannel.runPendingTasks();
            secondChannel.runPendingTasks();
            firstFrame = firstChannel.readOutbound();
            secondFrame = secondChannel.readOutbound();
            assertNotNull(firstFrame);
            assertNotNull(secondFrame);

            assertEquals(Events.NETWORK_MESSAGE, firstFrame.readUnsignedByte());
            assertEquals(1234, firstFrame.readInt());
            firstFrame.release();
            firstFrame = null;
            assertEquals(Events.NETWORK_MESSAGE, secondFrame.readUnsignedByte());
            assertEquals(1234, secondFrame.readInt());
            secondFrame.release();
            secondFrame = null;
            assertEquals(inbound ? 0 : 1, payload.refCnt(), "Only the external caller keeps its original payload reference");
        } finally {
            game.close();
            release(firstFrame);
            release(secondFrame);
            firstChannel.finishAndReleaseAll();
            secondChannel.finishAndReleaseAll();
            release(payload);
        }
    }

    /**
     * Verifies the regression scenario: queued payload is retained until dispatcher closes.
     *
     * @throws Exception if executor startup or a completion barrier fails
     */
    @Test
    void queuedPayloadIsRetainedUntilDispatcherCloses() throws Exception {
        DispatcherTestSupport.PausedDispatcher paused = new DispatcherTestSupport.PausedDispatcher(64);
        NettyEventDispatcher dispatcher = paused.dispatcher;
        ByteBuf payload = Unpooled.buffer().writeByte(1);
        try {
            dispatcher.fireEvent(Events.event(new NettyMessageBuffer(payload), Events.START));
            assertEquals(2, payload.refCnt());
            payload.release();

            dispatcher.close();

            assertEquals(0, payload.refCnt());
        } finally {
            paused.close();
            release(payload);
        }
    }

    /**
     * Verifies the regression scenario: dropped payload does not acquire a reference.
     */
    @Test
    void droppedPayloadDoesNotAcquireAReference() {
        NettyEventDispatcher dispatcher = new NettyEventDispatcher();
        dispatcher.close();
        ByteBuf payload = Unpooled.buffer().writeByte(1);
        try {
            assertFalse(dispatcher.fireEvent(Events.event(payload, Events.START)));
            assertEquals(1, payload.refCnt());
        } finally {
            release(payload);
        }
    }

    /**
     * Verifies the regression scenario: failed network write releases its payload reference.
     */
    @Test
    void failedNetworkWriteReleasesItsPayloadReference() {
        EmbeddedChannel channel = new EmbeddedChannel(new MessageBufferEventEncoder());
        ByteBuf payload = Unpooled.buffer().writeByte(1);
        channel.close();
        try {
            var future = (io.netty.channel.ChannelFuture) new SocketMessageSender(channel)
                    .sendMessage(Events.networkEvent(new NettyMessageBuffer(payload)));
            channel.runPendingTasks();

            assertTrue(future.isDone());
            assertFalse(future.isSuccess());
            assertEquals(1, payload.refCnt());
        } finally {
            channel.finishAndReleaseAll();
            release(payload);
        }
    }

    /**
     * Verifies the regression scenario: rejected inbound message releases payload.
     */
    @Test
    void rejectedInboundMessageReleasesPayload() {
        DefaultGame game = new DefaultGame();
        PlayerSession player = game.createPlayerSession();
        player.close();
        EmbeddedChannel channel = new EmbeddedChannel(new DefaultToServerHandler(player));
        ByteBuf payload = Unpooled.buffer().writeByte(1);
        try {
            channel.writeInbound(Events.event(new NettyMessageBuffer(payload), Events.SESSION_MESSAGE));
            assertEquals(0, payload.refCnt());
        } finally {
            channel.finishAndReleaseAll();
            game.close();
            release(payload);
        }
    }

    /**
     * Verifies the regression scenario: queue overflow releases the rejected delivery reference.
     *
     * @throws Exception if executor startup or a completion barrier fails
     */
    @Test
    void queueOverflowReleasesTheRejectedDeliveryReference() throws Exception {
        DispatcherTestSupport.PausedDispatcher paused = new DispatcherTestSupport.PausedDispatcher(64);
        NettyEventDispatcher dispatcher = paused.dispatcher;
        ByteBuf payload = Unpooled.buffer().writeByte(1);
        Event event = Events.event(payload, Events.START);
        try {
            for (int i = 0; i < dispatcher.getQueueCapacity(); i++) {
                dispatcher.fireEvent(event);
            }
            assertEquals(dispatcher.getQueueCapacity() + 1, payload.refCnt());

            assertFalse(dispatcher.fireEvent(event));

            assertEquals(1, dispatcher.getRejectedEventCount());
            assertEquals(dispatcher.getQueueCapacity(), dispatcher.getQueueSize());
            assertEquals(dispatcher.getQueueCapacity() + 1, payload.refCnt());
            dispatcher.close();
            assertEquals(1, payload.refCnt());
        } finally {
            paused.close();
            release(payload);
        }
    }

    /**
     * Verifies the regression scenario: changing queued event source does not leak its original
     * reference.
     *
     * @throws Exception if executor startup or a completion barrier fails
     */
    @Test
    void changingQueuedEventSourceDoesNotLeakItsOriginalReference() throws Exception {
        DispatcherTestSupport.PausedDispatcher paused = new DispatcherTestSupport.PausedDispatcher(64);
        NettyEventDispatcher dispatcher = paused.dispatcher;
        ByteBuf original = Unpooled.buffer().writeByte(1);
        ByteBuf replacement = Unpooled.buffer().writeByte(2);
        try {
            Event event = Events.event(original, Events.START);
            dispatcher.fireEvent(event);
            event.setSource(replacement);

            dispatcher.close();

            assertEquals(1, original.refCnt());
            assertEquals(1, replacement.refCnt());
        } finally {
            paused.close();
            release(original);
            release(replacement);
        }
    }

    /**
     * Verifies the regression scenario: failing handler releases queue and task payload references.
     *
     * @throws Exception if executor startup or a completion barrier fails
     */
    @Test
    void failingHandlerReleasesQueueAndTaskPayloadReferences() throws Exception {
        NettyEventDispatcher dispatcher = new NettyEventDispatcher();
        ByteBuf payload = Unpooled.buffer().writeByte(1);
        dispatcher.addHandler(new EventHandler() {
            @Override
            public int getEventType() { return Events.START; }

            @Override
            public void onEvent(Event event) { throw new IllegalStateException("Injected handler failure"); }
        });
        try {
            dispatcher.fireEvent(Events.event(payload, Events.START));
            DispatcherTestSupport.drain(dispatcher);
            assertEquals(1, payload.refCnt());
        } finally {
            dispatcher.close();
            release(payload);
        }
    }

    /**
     * Verifies the regression scenario: copying converted payload releases temporary buffer on
     * success and failure.
     *
     * @param readOnly the read only
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void copyingConvertedPayloadReleasesTemporaryBufferOnSuccessAndFailure(boolean readOnly) {
        ByteBuf target = Unpooled.buffer();
        ByteBuf converted = Unpooled.buffer().writeInt(1234);
        NettyMessageBuffer message = new NettyMessageBuffer(readOnly ? target.asReadOnly() : target);
        try {
            if (readOnly) {
                assertThrows(java.nio.ReadOnlyBufferException.class, () -> message.writeObject(value -> converted, "value"));
            } else {
                message.writeObject(value -> converted, "value");
                assertEquals(4, message.readUnsignedShort());
                assertEquals(1234, message.readInt());
            }
            assertEquals(0, converted.refCnt());
        } finally {
            release(target);
            release(converted);
        }
    }

    /**
     * Verifies the regression scenario: player.
     *
     * @param game the associated game
     * @param id the identifier
     * @param channel the Netty transport channel
     * @return the value used by the regression scenario
     */
    private static PlayerSession player(DefaultGame game, String id, EmbeddedChannel channel) {
        var builder = new com.akakata.app.impl.DefaultPlayerSession.PlayerSessionBuilder().game(game);
        builder.id(id);
        PlayerSession session = builder.build();
        session.setSender(new SocketMessageSender(channel));
        assertTrue(game.connectSession(session));
        game.onLogin(session);
        return session;
    }

    /**
     * Verifies the regression scenario: drain.
     *
     * @param session the session associated with the operation
     * @throws Exception if the operation fails
     */
    private static void drain(PlayerSession session) throws Exception {
        DispatcherTestSupport.drain(session.getEventDispatcher());
        DispatcherTestSupport.drain(session.getGame().getEventDispatcher());
        DispatcherTestSupport.drain(session.getEventDispatcher());
    }

    /**
     * Verifies the regression scenario: release.
     *
     * @param buffer the source native buffer
     */
    private static void release(ByteBuf buffer) {
        if (buffer != null && buffer.refCnt() > 0) {
            buffer.release();
        }
    }
}
