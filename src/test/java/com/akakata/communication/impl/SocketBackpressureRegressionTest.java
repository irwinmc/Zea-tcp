package com.akakata.communication.impl;

import com.akakata.communication.OutboundLimits;
import com.akakata.event.Events;
import com.akakata.handlers.codec.JsonDecoder;
import com.akakata.handlers.codec.JsonEncoder;
import com.akakata.handlers.codec.MessageBufferEventEncoder;
import com.akakata.handlers.codec.WebSocketEventDecoder;
import com.akakata.handlers.codec.WebSocketEventEncoder;
import com.akakata.event.Event;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.lang.reflect.Proxy;
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
     * Honors channel writability before retaining a rejected Event source.
     */
    @Test
    void unwritableChannelRejectsWithoutRetainingTheCallerOwnedEventSource() {
        EmbeddedChannel channel = new EmbeddedChannel(new MessageBufferEventEncoder());
        SocketMessageSender sender = new SocketMessageSender(channel);
        ByteBuf payload = Unpooled.buffer().writeByte(1);
        try {
            channel.unsafe().outboundBuffer().setUserDefinedWritability(1, false);
            var future = sender.sendMessage(Events.networkEvent(new NettyMessageBuffer(payload)));
            assertInstanceOf(RejectedExecutionException.class, future.cause());
            assertEquals(1, payload.refCnt());
            assertFalse(channel.isOpen());
        } finally {
            channel.finishAndReleaseAll();
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
