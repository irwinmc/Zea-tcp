package com.akakata.handlers;

import com.akakata.server.ConnectionAdmission;
import com.akakata.server.HttpLimits;
import com.akakata.server.HttpAdmission;
import com.akakata.server.TransportLimits;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies shared HTTP socket admission, slow-request deadlines and aggregation limits.
 */
class HttpResourceRegressionTest {

    /**
     * Creates the HTTP resource fixture.
     */
    HttpResourceRegressionTest() {
    }

    /**
     * Shares connection slots with game sockets while keeping HTTP free of login expiry.
     */
    @Test
    void httpAndTcpShareSlotsAndReturnThemExactlyOnce() {
        ConnectionAdmission admission = new ConnectionAdmission(new TransportLimits(1, 10, 0, 1024));
        EmbeddedChannel http = new EmbeddedChannel(new HttpLifecycleHandler(new HttpAdmission(admission, new HttpLimits(64, 100, 0))));
        EmbeddedChannel rejected = new EmbeddedChannel(new TransportLifecycleHandler(admission));
        try {
            assertFalse(rejected.isOpen());
            assertEquals(1, admission.activeConnections());
            assertTrue(admission.admit(http));
            http.pipeline().fireChannelActive();
            assertEquals(1, admission.activeConnections());
            http.advanceTimeBy(11, TimeUnit.MILLISECONDS);
            http.runScheduledPendingTasks();
            assertTrue(http.isActive(), "HTTP must not use the game login deadline");
            http.close();
            http.close();
            assertEquals(0, admission.activeConnections());
            EmbeddedChannel replacement = new EmbeddedChannel(new TransportLifecycleHandler(admission));
            try {
                assertTrue(replacement.isActive());
                assertEquals(1, admission.activeConnections());
            } finally {
                replacement.finishAndReleaseAll();
            }
            assertEquals(0, admission.activeConnections());
        } finally {
            http.finishAndReleaseAll();
            rejected.finishAndReleaseAll();
        }
    }

    /**
     * Partial content and repeated activation cannot extend an absolute request deadline.
     */
    @Test
    void incompleteRequestExpiresEvenWhenBytesKeepArriving() {
        ConnectionAdmission admission = new ConnectionAdmission(new TransportLimits(2, 10, 0, 1024));
        EmbeddedChannel channel = new EmbeddedChannel(new HttpLifecycleHandler(new HttpAdmission(admission, new HttpLimits(64, 100, 0))),
                new HttpObjectAggregator(64), new io.netty.channel.ChannelInboundHandlerAdapter() {
                    @Override
                    public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable failure) {
                        if (!(failure instanceof io.netty.handler.codec.PrematureChannelClosureException)) {
                            ctx.fireExceptionCaught(failure);
                        }
                    }
                });
        var request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/api");
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, 64);
        try {
            channel.writeInbound(request);
            channel.advanceTimeBy(80, TimeUnit.MILLISECONDS);
            channel.pipeline().fireChannelActive();
            var content = new DefaultHttpContent(Unpooled.buffer().writeByte(1));
            channel.writeInbound(content);
            channel.advanceTimeBy(21, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertFalse(channel.isOpen());
            assertEquals(0, admission.activeConnections());
            assertEquals(0, content.refCnt(), "Timeout must release a partially aggregated body");
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Resets the deadline only after a completed request and enforces the configured body size.
     */
    @Test
    void completedRequestsRenewTheDeadlineAndOversizedBodiesAreRejected() {
        ConnectionAdmission admission = new ConnectionAdmission(new TransportLimits(2, 10, 0, 1024));
        EmbeddedChannel channel = new EmbeddedChannel(new HttpLifecycleHandler(new HttpAdmission(admission, new HttpLimits(4, 100, 0))),
                new HttpObjectAggregator(4));
        try {
            channel.advanceTimeBy(80, TimeUnit.MILLISECONDS);
            channel.writeInbound(new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/health"));
            channel.writeInbound(new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER));
            io.netty.util.ReferenceCountUtil.release(channel.readInbound());
            channel.advanceTimeBy(21, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
            assertTrue(channel.isActive());
            var oversized = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/api");
            oversized.headers().set(HttpHeaderNames.CONTENT_LENGTH, 5);
            channel.writeInbound(oversized);
            FullHttpResponse rejected = channel.readOutbound();
            assertNotNull(rejected);
            try {
                assertEquals(HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE, rejected.status());
            } finally {
                rejected.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
        assertEquals(0, admission.activeConnections());
    }

    /**
     * Keeps an HTTP-specific limit within the total allowance and leaves capacity for game sockets.
     */
    @Test
    void httpCannotConsumeMoreThanItsOwnConnectionAllowance() {
        ConnectionAdmission total = new ConnectionAdmission(new TransportLimits(4, 100, 0, 1024));
        HttpAdmission http = new HttpAdmission(total, new HttpLimits(64, 100, 0, 1));
        EmbeddedChannel first = new EmbeddedChannel(new HttpLifecycleHandler(http));
        EmbeddedChannel rejected = new EmbeddedChannel(new HttpLifecycleHandler(http));
        EmbeddedChannel tcp = new EmbeddedChannel(new TransportLifecycleHandler(total));
        try {
            assertTrue(first.isActive());
            assertFalse(rejected.isOpen());
            assertTrue(tcp.isActive());
            assertEquals(1, http.activeConnections());
            assertEquals(2, total.activeConnections());
            first.close();
            assertEquals(0, http.activeConnections());
            assertEquals(1, total.activeConnections());
        } finally {
            first.finishAndReleaseAll();
            rejected.finishAndReleaseAll();
            tcp.finishAndReleaseAll();
        }
        assertEquals(0, total.activeConnections());
    }

}
