package com.akakata.server.http;

import com.akakata.lifecycle.ServerLifecycle;
import com.akakata.metrics.ServerHealth;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies native response-source cleanup on successful and rejected HTTP writes.
 */
class HttpResponseOwnershipRegressionTest {

    /**
     * Creates the response ownership fixture.
     */
    HttpResponseOwnershipRegressionTest() {
    }

    /**
     * Releases pooled health content on every route and on transport rejection.
     *
     * @param path   health route
     * @param reject whether to reject the encoded response
     */
    @ParameterizedTest
    @CsvSource({"/health,false", "/health/live,false", "/health/ready?probe=1,false",
            "/health,true", "/health/live,true", "/health/ready,true"})
    void healthReleasesOwnedSource(String path, boolean reject) {
        AtomicReference<ByteBuf> source = new AtomicReference<>();
        ServerHealth health = () -> new ServerHealth.Snapshot(ServerLifecycle.Phase.RUNNING, true, true, Map.of("game", true));
        HealthCheckHandler presenter = new HealthCheckHandler(health) {
            @Override
            protected ByteBuf createContent(String json) {
                ByteBuf content = PooledByteBufAllocator.DEFAULT.directBuffer();
                content.writeCharSequence(json, CharsetUtil.UTF_8);
                source.set(content);
                return content;
            }
        };
        EmbeddedChannel channel = channel(reject, (context, request) -> presenter.handleHealthCheck(context, request));
        try {
            channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path));
            assertEquals(0, source.get().refCnt());
            if (!reject) {
                FullHttpResponse response = channel.readOutbound();
                try {
                    assertEquals(200, response.status().code());
                    assertTrue(response.content().toString(CharsetUtil.UTF_8).contains("RUNNING"));
                } finally {
                    response.release();
                }
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Releases owned index content even when response construction fails.
     */
    @Test
    void indexReleasesSourceAfterCopyFailure() {
        ByteBuf source = PooledByteBufAllocator.DEFAULT.directBuffer().writeByte(1);
        IndexPageHandler index = new IndexPageHandler() {
            @Override
            protected ByteBuf getContent() {
                return source;
            }

            @Override
            protected void sendHttpResponse(ChannelHandlerContext context, ByteBuf content) {
                super.sendHttpResponse(context, content, null);
            }
        };
        EmbeddedChannel channel = channel(false, index::createIndexPage);
        try {
            assertThrows(RuntimeException.class, () -> channel.writeInbound(
                    new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")));
            assertEquals(0, source.refCnt());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Releases pooled index content after successful and rejected handoffs.
     *
     * @param reject whether to reject the response write
     */
    @ParameterizedTest
    @CsvSource({"false", "true"})
    void indexReleasesItsSourceAfterCopy(boolean reject) {
        ByteBuf source = PooledByteBufAllocator.DEFAULT.directBuffer();
        source.writeCharSequence("index", CharsetUtil.UTF_8);
        IndexPageHandler index = new IndexPageHandler() {
            @Override
            protected ByteBuf getContent() {
                return source;
            }
        };
        EmbeddedChannel channel = channel(reject, index::createIndexPage);
        try {
            channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"));
            assertEquals(0, source.refCnt());
            if (!reject) {
                FullHttpResponse response = channel.readOutbound();
                try {
                    assertEquals("index", response.content().toString(CharsetUtil.UTF_8));
                } finally {
                    response.release();
                }
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Uses a JSON body and HTTP 503 for unavailable readiness.
     */
    @Test
    void unavailableReadinessReturnsJsonWithItsStatus() {
        HealthCheckHandler health = new HealthCheckHandler(() -> new ServerHealth.Snapshot(
                ServerLifecycle.Phase.STOPPING, true, false, Map.of("game", false)));
        EmbeddedChannel channel = channel(false, health::handleHealthCheck);
        try {
            channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/health/ready"));
            FullHttpResponse response = channel.readOutbound();
            try {
                assertEquals(503, response.status().code());
                assertEquals("application/json; charset=UTF-8", response.headers().get("Content-Type"));
                assertTrue(response.content().toString(CharsetUtil.UTF_8).contains("STOPPING"));
            } finally {
                response.release();
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Builds a response fixture with an optional ownership-correct transport failure.
     *
     * @param reject whether writes fail after releasing their response ownership
     * @param route  borrowed request consumer
     * @return fixture channel
     */
    private static EmbeddedChannel channel(boolean reject, Route route) {
        EmbeddedChannel channel = new EmbeddedChannel();
        if (reject) {
            channel.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                    ReferenceCountUtil.release(message);
                    promise.setFailure(new IllegalStateException("Injected transport rejection"));
                }
            });
        }
        channel.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
            @Override
            protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) throws Exception {
                route.handle(context, request);
            }
        });
        return channel;
    }

    /**
     * Typed HTTP presenter used by the ownership fixture.
     */
    @FunctionalInterface
    private interface Route {

        /**
         * Presents one borrowed request.
         *
         * @param context channel context
         * @param request borrowed request
         * @throws Exception if presentation fails
         */
        void handle(ChannelHandlerContext context, FullHttpRequest request) throws Exception;
    }
}
