package com.akakata.communication.impl;

import com.akakata.communication.OutboundBudget;
import com.akakata.communication.OutboundLimits;
import com.akakata.event.Event;
import com.akakata.event.Events;
import com.akakata.handlers.codec.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies bounded socket admission, encoded-byte accounting and TCP/WebSocket write order.
 */
class SocketBackpressureRegressionTest {

    /**
     * Creates the regression fixture.
     */
    SocketBackpressureRegressionTest() {
    }

    /**
     * Keeps accepted writes in FIFO order through the real event codecs.
     *
     * @param websocket whether to use WebSocket rather than TCP JSON events
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptedWritesRemainOrderedThroughTcpAndWebSocketCodecs(boolean websocket) {
        EmbeddedChannel channel = websocket
                ? new EmbeddedChannel(new WebSocket13FrameEncoder(false), new WebSocketEventEncoder())
                : new EmbeddedChannel(new LengthFieldPrepender(2), new JsonEncoder());
        EmbeddedChannel decoder = websocket
                ? new EmbeddedChannel(new WebSocket13FrameDecoder(false, false, 65_535), new WebSocketEventDecoder())
                : new EmbeddedChannel(new LengthFieldBasedFrameDecoder(65_535, 0, 2, 0, 2), new JsonDecoder());
        SocketMessageSender sender = new SocketMessageSender(channel, new OutboundLimits(64, 4_096));
        List<ChannelFuture> writes = new ArrayList<>();
        try {
            for (int i = 0; i < 32; i++) {
                writes.add(sender.sendMessage(Events.networkEvent(Map.of("sequence", i))));
            }
            assertEquals(32, sender.pendingWrites());
            channel.runPendingTasks();
            Object frame;
            while ((frame = channel.readOutbound()) != null) {
                decoder.writeInbound(frame);
            }
            for (int i = 0; i < 32; i++) {
                assertTrue(writes.get(i).isSuccess());
                Event event = decoder.readInbound();
                assertNotNull(event);
                assertEquals(Map.of("sequence", i), event.getSource());
            }
            assertEquals(0, sender.pendingWrites());
            assertNull(channel.readOutbound());
            assertNull(decoder.readInbound());
        } finally {
            channel.finishAndReleaseAll();
            decoder.finishAndReleaseAll();
        }
    }

    /**
     * Shares one canonical JSON application frame across TCP and WebSocket recipients.
     */
    @Test
    void mixedTransportFanOutEncodesOnceAndReleasesSharedLease() {
        EmbeddedChannel tcp = new EmbeddedChannel(new JsonEncoder());
        EmbeddedChannel websocket = new EmbeddedChannel(new WebSocketEventEncoder());
        var shared = new com.akakata.event.impl.SharedNetworkEvent(Events.networkEvent(Map.of("sequence", 42)));
        long encoded = com.akakata.metrics.FlowControlMetrics.ENCODED_FRAMES.sum();
        long reused = com.akakata.metrics.FlowControlMetrics.ENCODING_REUSES.sum();
        try {
            var first = new SocketMessageSender(tcp).sendMessage(shared);
            var second = new SocketMessageSender(websocket).sendMessage(shared);
            shared.releaseOwner();
            tcp.runPendingTasks();
            websocket.runPendingTasks();
            assertTrue(first.isSuccess());
            assertTrue(second.isSuccess());
            assertEquals(0, shared.payloadLease().refCnt());
            ByteBuf tcpFrame = tcp.readOutbound();
            var wsFrame = (io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame) websocket.readOutbound();
            try {
                assertEquals(tcpFrame, wsFrame.content());
                int originalIndex = wsFrame.content().readerIndex();
                tcpFrame.readByte();
                assertEquals(originalIndex, wsFrame.content().readerIndex());
                assertTrue(tcpFrame.isReadOnly());
                assertEquals(encoded + 1, com.akakata.metrics.FlowControlMetrics.ENCODED_FRAMES.sum());
                assertEquals(reused + 1, com.akakata.metrics.FlowControlMetrics.ENCODING_REUSES.sum());
            } finally {
                tcpFrame.release();
                wsFrame.release();
            }
        } finally {
            tcp.finishAndReleaseAll();
            websocket.finishAndReleaseAll();
        }
    }

    /**
     * Keeps shared native sources alive across recipient failure and successful binary encoding.
     */
    @Test
    void cancelledRecipientCannotReleaseAnotherRecipientsCachedBinaryPayload() {
        EmbeddedChannel good = new EmbeddedChannel(new com.akakata.handlers.codec.SbeEventEncoder());
        EmbeddedChannel bad = new EmbeddedChannel(new com.akakata.handlers.codec.SbeEventEncoder());
        ByteBuf payload = Unpooled.buffer().writeInt(42);
        var shared = new com.akakata.event.impl.SharedNetworkEvent(Events.networkEvent(payload));
        try {
            var first = new SocketMessageSender(good).sendMessage(shared);
            var second = new SocketMessageSender(bad, new OutboundLimits(8, 1)).sendMessage(shared);
            payload.release();
            shared.releaseOwner();
            bad.runPendingTasks();
            assertFalse(second.isSuccess());
            assertEquals(1, payload.refCnt());
            good.runPendingTasks();
            assertTrue(first.isSuccess());
            assertEquals(0, payload.refCnt());
            assertEquals(0, shared.payloadLease().refCnt());
            ByteBuf frame = good.readOutbound();
            try {
                assertEquals(42, frame.getInt(frame.readerIndex() + 8));
            } finally {
                frame.release();
            }
        } finally {
            good.finishAndReleaseAll();
            bad.finishAndReleaseAll();
        }
    }

    /**
     * Flushes bounded batches while preserving admission order over multiple drain tasks.
     */
    @Test
    void socketWritesFlushOncePerBoundedBatchAndPreserveOrder() {
        java.util.concurrent.atomic.AtomicInteger flushes = new java.util.concurrent.atomic.AtomicInteger();
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelDuplexHandler() {
            @Override
            public void flush(ChannelHandlerContext ctx) {
                flushes.incrementAndGet();
                ctx.flush();
            }
        });
        SocketMessageSender sender = new SocketMessageSender(channel, new OutboundLimits(256, 4096));
        try {
            for (int i = 0; i < 130; i++) {
                sender.sendMessage(Unpooled.buffer().writeInt(i));
            }
            channel.runPendingTasks();
            assertEquals(3, flushes.get());
            assertEquals(0, sender.pendingWrites());
            for (int i = 0; i < 130; i++) {
                ByteBuf frame = channel.readOutbound();
                try {
                    assertEquals(i, frame.readInt());
                } finally {
                    frame.release();
                }
            }
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Limits tasks admitted before the I/O loop runs and releases all native ownership on overflow.
     */
    @Test
    void pendingWriteOverflowClosesTransportAndReleasesQueuedEventReferences() {
        EmbeddedChannel channel = new EmbeddedChannel(new MessageBufferEventEncoder());
        SocketMessageSender sender = new SocketMessageSender(channel, new OutboundLimits(2, 1_024));
        ByteBuf payload = Unpooled.buffer().writeInt(1);
        try {
            var event = Events.networkEvent(new NettyMessageBuffer(payload));
            ChannelFuture first = sender.sendMessage(event);
            ChannelFuture second = sender.sendMessage(event);
            assertEquals(3, payload.refCnt());
            ChannelFuture rejected = sender.sendMessage(event);
            assertInstanceOf(RejectedExecutionException.class, rejected.cause());
            channel.runPendingTasks();
            assertFalse(channel.isOpen());
            assertTrue(first.isDone());
            assertTrue(second.isDone());
            assertFalse(first.isSuccess());
            assertFalse(second.isSuccess());
            assertEquals(1, payload.refCnt());
            assertEquals(0, sender.pendingWrites());
            assertNull(channel.readOutbound());
        } finally {
            channel.finishAndReleaseAll();
            payload.release();
        }
    }

    /**
     * Rejects a single encoded frame that exceeds the byte budget without leaking its source.
     */
    @Test
    void oversizedEncodedFrameFailsAndReleasesAllTransferredReferences() {
        EmbeddedChannel channel = new EmbeddedChannel(new MessageBufferEventEncoder());
        SocketMessageSender sender = new SocketMessageSender(channel, new OutboundLimits(8, 8));
        ByteBuf payload = Unpooled.buffer().writeZero(32);
        try {
            ChannelFuture write = sender.sendMessage(Events.networkEvent(new NettyMessageBuffer(payload)));
            payload.release();
            channel.runPendingTasks();
            assertInstanceOf(RejectedExecutionException.class, write.cause());
            assertFalse(channel.isOpen());
            assertEquals(0, payload.refCnt());
            assertEquals(0, sender.pendingWrites());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Counts bytes across distinct pending transport writes and frees them when the channel closes.
     */
    @Test
    void cumulativeEncodedBytesCannotExceedTheConnectionBudget() {
        EmbeddedChannel channel = new EmbeddedChannel(new MessageBufferEventEncoder());
        SocketMessageSender sender = new SocketMessageSender(channel, new OutboundLimits(8, 12));
        ByteBuf warmup = Unpooled.buffer().writeByte(0);
        sender.sendMessage(Events.networkEvent(new NettyMessageBuffer(warmup)));
        channel.runPendingTasks();
        ReferenceCountUtil.release(channel.readOutbound());
        warmup.release();
        channel.pipeline().addFirst(new HoldingTransport());
        ByteBuf first = Unpooled.buffer().writeLong(1);
        ByteBuf second = Unpooled.buffer().writeLong(2);
        try {
            ChannelFuture one = sender.sendMessage(Events.networkEvent(new NettyMessageBuffer(first)));
            ChannelFuture two = sender.sendMessage(Events.networkEvent(new NettyMessageBuffer(second)));
            first.release();
            second.release();
            channel.runPendingTasks();
            assertFalse(channel.isOpen());
            assertTrue(one.isDone());
            assertInstanceOf(RejectedExecutionException.class, two.cause());
            assertEquals(0, first.refCnt());
            assertEquals(0, second.refCnt());
            assertEquals(0, sender.pendingWrites());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Keeps temporary water-mark pressure in the bounded FIFO and resumes without losing ownership.
     */
    @Test
    void unwritableChannelPausesAndResumesTheCallerOwnedEventSource() {
        EmbeddedChannel channel = new EmbeddedChannel(new MessageBufferEventEncoder());
        OutboundBudget budget = new OutboundBudget(1024);
        SocketMessageSender sender = new SocketMessageSender(channel, OutboundLimits.DEFAULT, budget);
        ByteBuf payload = Unpooled.buffer().writeByte(1);
        try {
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
            var future = sender.sendMessage(Events.networkEvent(new NettyMessageBuffer(payload)));
            channel.runPendingTasks();
            assertFalse(future.isDone());
            assertEquals(2, payload.refCnt());
            assertTrue(channel.isOpen());
            assertNull(channel.readOutbound());
            assertEquals(1, sender.pendingWrites());
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, true);
            channel.runPendingTasks();
            assertTrue(future.isSuccess());
            ReferenceCountUtil.release(channel.readOutbound());
            assertEquals(1, payload.refCnt());
            assertEquals(0, budget.pendingBytes());
            assertEquals(0, sender.pendingWrites());
        } finally {
            channel.finishAndReleaseAll();
            payload.release();
        }
    }

    /**
     * Flushes large bursts below the default water mark through both production event encoders.
     *
     * @param websocket whether to use WebSocket instead of TCP framing
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void largeBurstSurvivesTheDefaultWaterMark(boolean websocket) {
        EmbeddedChannel channel = websocket
                ? new EmbeddedChannel(new WebSocket13FrameEncoder(false), new WebSocketEventEncoder())
                : new EmbeddedChannel(new LengthFieldPrepender(2), new JsonEncoder());
        EmbeddedChannel decoder = websocket
                ? new EmbeddedChannel(new WebSocket13FrameDecoder(false, false, 65_535), new WebSocketEventDecoder())
                : new EmbeddedChannel(new LengthFieldBasedFrameDecoder(65_535, 0, 2, 0, 2), new JsonDecoder());
        OutboundBudget budget = new OutboundBudget(4 * 1024 * 1024);
        SocketMessageSender sender = new SocketMessageSender(channel, OutboundLimits.DEFAULT, budget);
        List<ChannelFuture> writes = new ArrayList<>();
        try {
            for (int sequence = 0; sequence < 64; sequence++) {
                writes.add(sender.sendMessage(Events.networkEvent(Map.of("sequence", sequence, "payload", "x".repeat(4096)))));
            }
            channel.runPendingTasks();
            Object frame;
            while ((frame = channel.readOutbound()) != null) {
                decoder.writeInbound(frame);
            }
            for (int sequence = 0; sequence < 64; sequence++) {
                assertTrue(writes.get(sequence).isSuccess());
                Event response = decoder.readInbound();
                assertEquals(sequence, ((Map<?, ?>) response.getSource()).get("sequence"));
            }
            assertTrue(channel.isActive());
            assertEquals(0, sender.pendingWrites());
            assertEquals(0, sender.pendingPayloadBytes());
            assertEquals(0, budget.pendingBytes());
            assertNull(decoder.readInbound());
        } finally {
            channel.finishAndReleaseAll();
            decoder.finishAndReleaseAll();
        }
    }

    /**
     * Bounds stalled queued and forwarded writes with one deadline and returns every native credit.
     *
     * @param forwarded whether the write has already entered a stalled outbound handler
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stalledWriteDeadlineClosesAndReleasesBothOwnershipStages(boolean forwarded) {
        EmbeddedChannel channel = new EmbeddedChannel(new MessageBufferEventEncoder());
        OutboundBudget budget = new OutboundBudget(1024);
        SocketMessageSender sender = new SocketMessageSender(channel, OutboundLimits.DEFAULT, budget, 1000);
        ByteBuf payload = Unpooled.buffer().writeInt(42);
        long timeouts = com.akakata.metrics.FlowControlMetrics.WRITE_TIMEOUTS.sum();
        try {
            if (forwarded) {
                SocketMessageSender.installOutboundBudget(channel.pipeline(), OutboundLimits.DEFAULT, budget);
                channel.pipeline().addFirst(new HoldingTransport());
            } else {
                channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
            }
            ChannelFuture future = sender.sendMessage(Events.networkEvent(new NettyMessageBuffer(payload)));
            payload.release();
            channel.runPendingTasks();
            assertFalse(future.isDone());
            assertTrue(budget.pendingBytes() > 0);
            channel.advanceTimeBy(2, java.util.concurrent.TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            channel.runPendingTasks();
            assertFalse(channel.isOpen());
            assertTrue(future.isDone());
            assertFalse(future.isSuccess());
            assertEquals(0, payload.refCnt());
            assertEquals(0, sender.pendingWrites());
            assertEquals(0, sender.pendingPayloadBytes());
            assertEquals(0, budget.pendingBytes());
            assertEquals(timeouts + 1, com.akakata.metrics.FlowControlMetrics.WRITE_TIMEOUTS.sum());
        } finally {
            channel.finishAndReleaseAll();
            if (payload.refCnt() > 0) {
                payload.release();
            }
        }
    }

    /**
     * Returns completed source credits even if shutdown rejects the next oldest-write deadline.
     */
    @Test
    void rejectedDeadlineReplacementCannotLeakCompletedWriteCredits() {
        EmbeddedChannel transport = new EmbeddedChannel(new MessageBufferEventEncoder());
        var schedules = new java.util.concurrent.atomic.AtomicInteger();
        EventLoop loop = (EventLoop) Proxy.newProxyInstance(EventLoop.class.getClassLoader(),
                new Class<?>[]{EventLoop.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("schedule") && schedules.incrementAndGet() > 1) {
                        throw new RejectedExecutionException("Loop stopped during deadline replacement");
                    }
                    return method.invoke(transport.eventLoop(), arguments);
                });
        Channel channel = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(),
                new Class<?>[]{Channel.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("eventLoop")) {
                        return loop;
                    }
                    if (method.getName().equals("newPromise")) {
                        return new DefaultChannelPromise(transport, loop);
                    }
                    return method.invoke(transport, arguments);
                });
        OutboundBudget budget = new OutboundBudget(1024);
        SocketMessageSender sender = new SocketMessageSender(channel, OutboundLimits.DEFAULT, budget);
        ByteBuf payload = Unpooled.buffer().writeInt(42);
        try {
            var first = sender.sendMessage(Events.networkEvent(new NettyMessageBuffer(payload)));
            var second = sender.sendMessage(Events.networkEvent(new NettyMessageBuffer(payload)));
            transport.runPendingTasks();
            assertEquals(2, schedules.get());
            assertTrue(first.isDone());
            assertTrue(second.isDone());
            assertFalse(transport.isOpen());
            assertEquals(0, sender.pendingWrites());
            assertEquals(0, sender.pendingPayloadBytes());
            assertEquals(0, budget.pendingBytes());
            transport.finishAndReleaseAll();
            assertEquals(1, payload.refCnt());
        } finally {
            transport.finishAndReleaseAll();
            payload.release();
        }
    }

    /**
     * Releases ownership-transferred raw writes even if closure precedes their I/O task.
     */
    @Test
    void closingBeforeIoTaskReleasesRawMessagesAndCompletesTheirFutures() {
        EmbeddedChannel channel = new EmbeddedChannel();
        SocketMessageSender sender = new SocketMessageSender(channel);
        ByteBuf raw = Unpooled.buffer().writeInt(1);
        try {
            ChannelFuture future = sender.sendMessage(raw);
            assertFalse(future.cancel(false), "Cancellation must not free a source while an encoder uses it");
            sender.close();
            channel.runPendingTasks();
            assertTrue(future.isDone());
            assertFalse(future.isSuccess());
            assertEquals(0, raw.refCnt());
            assertEquals(0, sender.pendingWrites());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Frees queued ownership even when a stopped loop rejects both the write task and promise notification.
     *
     * @param eventSource whether the write uses a caller-owned Event source or a raw buffer
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stoppedEventLoopCannotLeakRejectedWrites(boolean eventSource) {
        EmbeddedChannel transport = new EmbeddedChannel();
        EventLoop stoppedLoop = (EventLoop) Proxy.newProxyInstance(EventLoop.class.getClassLoader(),
                new Class<?>[]{EventLoop.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("execute")) {
                        throw new RejectedExecutionException("Stopped regression event loop");
                    }
                    if (method.getName().equals("inEventLoop")) {
                        return false;
                    }
                    return method.invoke(transport.eventLoop(), arguments);
                });
        Channel channel = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(),
                new Class<?>[]{Channel.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("eventLoop")) {
                        return stoppedLoop;
                    }
                    if (method.getName().equals("newPromise")) {
                        return new DefaultChannelPromise(transport, stoppedLoop);
                    }
                    return method.invoke(transport, arguments);
                });
        SocketMessageSender sender = new SocketMessageSender(channel);
        ByteBuf payload = Unpooled.buffer().writeInt(1);
        try {
            Object message = eventSource ? Events.networkEvent(new NettyMessageBuffer(payload)) : payload;
            ChannelFuture future = sender.sendMessage(message);
            assertInstanceOf(RejectedExecutionException.class, future.cause());
            assertEquals(eventSource ? 1 : 0, payload.refCnt());
            assertEquals(0, sender.pendingWrites());
            assertFalse(transport.isOpen());
        } finally {
            transport.finishAndReleaseAll();
            if (payload.refCnt() > 0) {
                payload.release();
            }
        }
    }

    /**
     * Preserves resource accounting for direct pipeline writes using a void promise.
     */
    @Test
    void voidPromiseWritesStillReleaseTheirEncodedByteBudget() {
        EmbeddedChannel channel = new EmbeddedChannel(new OutboundBufferLimiter(new OutboundLimits(8, 8)));
        try {
            channel.writeAndFlush(Unpooled.buffer().writeLong(1), channel.voidPromise());
            ByteBuf first = channel.readOutbound();
            assertNotNull(first);
            first.release();
            channel.writeAndFlush(Unpooled.buffer().writeLong(2), channel.voidPromise());
            ByteBuf second = channel.readOutbound();
            assertNotNull(second, "A completed void-promise write must return its byte budget");
            second.release();
            assertTrue(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Prevents an I/O-loop caller from overtaking an older admitted write still in that loop's queue.
     */
    @Test
    void ioLoopSendCannotOvertakeAnOlderAdmittedWrite() {
        EmbeddedChannel channel = new EmbeddedChannel(new JsonEncoder());
        EmbeddedChannel decoder = new EmbeddedChannel(new JsonDecoder());
        SocketMessageSender sender = new SocketMessageSender(channel);
        try {
            sender.sendMessage(Events.networkEvent(Map.of("sequence", 1)));
            channel.eventLoop().execute(() -> sender.sendMessage(Events.networkEvent(Map.of("sequence", 3))));
            sender.sendMessage(Events.networkEvent(Map.of("sequence", 2)));
            channel.runPendingTasks();
            for (int i = 1; i <= 3; i++) {
                Object frame = channel.readOutbound();
                assertTrue(decoder.writeInbound(frame));
                Event event = decoder.readInbound();
                assertEquals(Map.of("sequence", i), event.getSource());
            }
        } finally {
            channel.finishAndReleaseAll();
            decoder.finishAndReleaseAll();
        }
    }

    /**
     * Rejects oversized sources before encoding or retaining a caller-owned Event payload.
     */
    @Test
    void preEncodingByteAdmissionRejectsLargeSourcesAndReleasesRawMessages() {
        for (boolean eventSource : new boolean[]{false, true}) {
            EmbeddedChannel channel = new EmbeddedChannel(new MessageBufferEventEncoder());
            OutboundBudget budget = new OutboundBudget(64);
            SocketMessageSender sender = new SocketMessageSender(channel, new OutboundLimits(8, 64, 4), budget);
            ByteBuf payload = Unpooled.buffer().writeLong(1);
            try {
                Object message = eventSource ? Events.networkEvent(new NettyMessageBuffer(payload)) : payload;
                var rejected = sender.sendMessage(message);
                assertInstanceOf(RejectedExecutionException.class, rejected.cause());
                assertEquals(eventSource ? 1 : 0, payload.refCnt());
                assertEquals(0, sender.pendingPayloadBytes());
                assertEquals(0, budget.pendingBytes());
                assertEquals(0, sender.pendingWrites());
                assertFalse(channel.isOpen());
            } finally {
                channel.finishAndReleaseAll();
                if (payload.refCnt() > 0) {
                    payload.release();
                }
            }
        }
    }

    /**
     * Applies one source allowance across independent channels and returns reservations on closure.
     */
    @Test
    void aggregateSourceBudgetIsSharedAcrossConnectionsAndReturnedAfterClosure() {
        OutboundBudget budget = new OutboundBudget(8);
        EmbeddedChannel first = new EmbeddedChannel();
        EmbeddedChannel second = new EmbeddedChannel();
        SocketMessageSender one = new SocketMessageSender(first, new OutboundLimits(8, 64, 64), budget);
        SocketMessageSender two = new SocketMessageSender(second, new OutboundLimits(8, 64, 64), budget);
        ByteBuf accepted = Unpooled.buffer().writeLong(1);
        ByteBuf rejected = Unpooled.buffer().writeByte(2);
        try {
            var admitted = one.sendMessage(accepted);
            assertEquals(8, one.pendingPayloadBytes());
            assertEquals(8, budget.pendingBytes());
            var failure = two.sendMessage(rejected);
            assertInstanceOf(RejectedExecutionException.class, failure.cause());
            assertEquals(0, rejected.refCnt());
            assertEquals(8, budget.pendingBytes());
            first.close();
            first.runPendingTasks();
            assertTrue(admitted.isDone());
            assertEquals(0, accepted.refCnt());
            assertEquals(0, one.pendingPayloadBytes());
            assertEquals(0, budget.pendingBytes());
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
        }
    }

    /**
     * Charges encoded expansion against the same aggregate owner as retained source bytes.
     */
    @Test
    void aggregateBudgetIncludesEncodedBytesAndSettlesBothChargesOnFailure() {
        EmbeddedChannel channel = new EmbeddedChannel(new MessageBufferEventEncoder());
        OutboundBudget budget = new OutboundBudget(12);
        SocketMessageSender sender = new SocketMessageSender(channel, new OutboundLimits(8, 64, 64), budget);
        ByteBuf payload = Unpooled.buffer().writeLong(1);
        try {
            var future = sender.sendMessage(Events.networkEvent(new NettyMessageBuffer(payload)));
            assertEquals(8, budget.pendingBytes());
            channel.runPendingTasks();
            assertInstanceOf(RejectedExecutionException.class, future.cause());
            assertFalse(channel.isOpen());
            assertEquals(1, payload.refCnt());
            assertEquals(0, budget.pendingBytes());
            assertEquals(0, sender.pendingPayloadBytes());
        } finally {
            payload.release();
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Holds encoded transport writes until channel closure to simulate a stalled client.
     */
    private static final class HoldingTransport extends ChannelDuplexHandler {

        /**
         * Native buffers whose transport completion is deliberately delayed.
         */
        private final List<Object> held = new ArrayList<>();

        /**
         * Creates the stalled transport fixture.
         */
        private HoldingTransport() {
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
            held.add(message);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            held.forEach(ReferenceCountUtil::release);
            held.clear();
            ctx.fireChannelInactive();
        }
    }
}
