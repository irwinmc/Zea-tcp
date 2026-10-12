package com.akakata.metrics;

import com.akakata.event.Events;
import com.akakata.handlers.ApplicationMetricsHandler;
import com.akakata.handlers.TransportMetricsHandler;
import com.akakata.util.NettyUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies transport and decoded-message counters at their actual ownership boundaries.
 */
class TransportMetricsRegressionTest {

    /**
     * Creates the instrumentation fixture.
     */
    TransportMetricsRegressionTest() {
    }

    /**
     * Counts one socket lifetime through duplicate activation and protocol replacement.
     */
    @Test
    void transportCountersSurviveProtocolReplacement() {
        ServerMetrics metrics = new ServerMetrics();
        EmbeddedChannel channel = new EmbeddedChannel(new TransportMetricsHandler(metrics));
        try {
            channel.pipeline().fireChannelActive();
            assertEquals(1, metrics.getTotalConnections());
            assertEquals(1, metrics.getActiveConnections());
            assertTrue(channel.writeInbound(Unpooled.buffer().writeBytes(new byte[]{1, 2, 3})));
            ByteBuf inbound = channel.readInbound();
            inbound.release();
            channel.pipeline().writeAndFlush(Unpooled.buffer().writeShort(1), channel.voidPromise());
            ByteBuf outbound = channel.readOutbound();
            outbound.release();
            assertEquals(3, metrics.getTotalBytesReceived());
            assertEquals(2, metrics.getTotalBytesSent());
            NettyUtils.clearPipeline(channel.pipeline());
            assertNotNull(channel.pipeline().get(TransportMetricsHandler.class));
            channel.close();
            assertEquals(0, metrics.getActiveConnections());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Excludes failed writes from successful byte totals and releases their native ownership.
     */
    @Test
    void failedTransportWriteRecordsErrorWithoutSuccessfulBytes() {
        ServerMetrics metrics = new ServerMetrics();
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                ReferenceCountUtil.release(message);
                promise.setFailure(new IllegalStateException("Injected write failure"));
            }
        }, new TransportMetricsHandler(metrics));
        ByteBuf buffer = Unpooled.directBuffer().writeByte(1);
        try {
            assertThrows(IllegalStateException.class, () -> channel.writeOutbound(buffer));
            assertEquals(0, buffer.refCnt());
            assertEquals(0, metrics.getTotalBytesSent());
            assertEquals(1, metrics.getTotalErrors());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Separates decoded business messages from login/control opcodes and malformed frames.
     */
    @Test
    void applicationCountersExcludeControlsAndCountDecoderFailures() {
        ServerMetrics metrics = new ServerMetrics();
        EmbeddedChannel channel = new EmbeddedChannel(new ApplicationMetricsHandler(metrics));
        try {
            channel.writeInbound(Events.event(null, Events.LOG_IN), Events.event(null, Events.LOG_OUT),
                    Events.event("business", Events.SESSION_MESSAGE));
            assertEquals(1, metrics.getTotalMessagesReceived());
            channel.pipeline().fireExceptionCaught(new DecoderException("Injected malformed frame"));
            assertThrows(DecoderException.class, channel::checkException);
            assertEquals(1, metrics.getTotalErrors());
            assertEquals(1, metrics.getTotalMessagesReceived());
        } finally {
            channel.finishAndReleaseAll();
        }
    }
}
