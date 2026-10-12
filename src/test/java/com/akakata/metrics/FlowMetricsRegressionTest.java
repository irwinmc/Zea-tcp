package com.akakata.metrics;

import com.akakata.app.impl.DefaultGame;
import com.akakata.communication.OutboundLimits;
import com.akakata.communication.impl.SocketMessageSender;
import com.akakata.event.Events;
import com.akakata.event.impl.DispatcherSnapshot;
import com.akakata.server.http.MetricsHandler;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies live Game metrics, exported flow-control schemas and transport gauge cleanup.
 */
class FlowMetricsRegressionTest {

    /**
     * Creates the flow metrics fixture.
     */
    FlowMetricsRegressionTest() {}

    /**
     * Exports separate Session/Game budgets and execution statistics through JSON and Prometheus.
     *
     * @throws Exception if queued execution or JSON parsing fails
     */
    @Test
    void endpointsIncludeLiveGameBudgetsOutcomesAndPercentiles() throws Exception {
        try (DefaultGame game = new DefaultGame(16, 1024);
             var blocking = new com.akakata.service.task.BlockingTaskExecutor(1, 2, 128, 1000)) {
            FlowControlMetrics.gameDispatcher(game.getEventDispatcher());
            FlowControlMetrics.resources(new com.akakata.communication.OutboundBudget(4096), blocking);
            game.getEventDispatcher().submit(Events.event(new byte[16], 1), ignored -> {}).get(3, TimeUnit.SECONDS);
            var flow = FlowControlMetrics.snapshot();
            DispatcherSnapshot snapshot = (DispatcherSnapshot) flow.get("game");
            assertEquals(1024, snapshot.byteBudget());
            assertEquals(1, snapshot.completed());
            assertEquals(0, snapshot.pendingBytes());
            assertTrue(snapshot.executionP99Micros() > 0);
            for (String route : new String[]{"/metrics/flow-control", "/metrics/prometheus", "/metrics"}) {
                ChannelInboundHandlerAdapter handler = new ChannelInboundHandlerAdapter();
                EmbeddedChannel channel = new EmbeddedChannel(handler);
                FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, route);
                try {
                    CapturingMetricsHandler metrics = new CapturingMetricsHandler();
                    metrics.handleMetrics(channel.pipeline().context(handler), request);
                    assertEquals(0, metrics.temporary.refCnt());
                    FullHttpResponse response = channel.readOutbound();
                    assertEquals(HttpResponseStatus.OK, response.status());
                    String body = response.content().toString(CharsetUtil.UTF_8);
                    try {
                        if (route.equals("/metrics/prometheus")) {
                            assertTrue(body.contains("zea_executor_byte_budget{scope=\"game\",shard=\"0\"} 1024"));
                            assertTrue(body.contains("zea_flow_pending_writes"));
                            assertTrue(body.contains("# TYPE zea_flow_pending_payload_bytes gauge"));
                            assertTrue(body.contains("zea_flow_outbound_byte_budget 4096"));
                            assertTrue(body.contains("zea_flow_blocking_byte_budget 128"));
                        } else {
                            Map<?, ?> document = new ObjectMapper().readValue(body, Map.class);
                            Map<?, ?> exported = route.equals("/metrics") ? (Map<?, ?>) document.get("flow_control") : document;
                            assertTrue(exported.containsKey("session"));
                            assertNotNull(exported.get("game"));
                            assertNotNull(exported.get("transport"));
                            assertNotNull(exported.get("outbound"));
                            assertNotNull(exported.get("blocking"));
                        }
                    } finally {
                        response.release();
                    }
                } finally {
                    request.release();
                    channel.finishAndReleaseAll();
                }
            }
        } finally {
            FlowControlMetrics.gameDispatcher(null);
            FlowControlMetrics.resources(null, null);
        }
    }

    /**
     * Returns admitted-write and encoded-byte gauges after queued transport cancellation.
     */
    @Test
    void failedQueuedWritesReturnEveryTransportGauge() {
        long baseline = FlowControlMetrics.PENDING_WRITES.sum();
        long bytes = FlowControlMetrics.PENDING_SOCKET_BYTES.sum();
        long sources = FlowControlMetrics.PENDING_PAYLOAD_BYTES.sum();
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            SocketMessageSender sender = new SocketMessageSender(channel, new OutboundLimits(1, 1024));
            var first = sender.sendMessage(Unpooled.buffer().writeByte(1));
            assertEquals(baseline + 1, FlowControlMetrics.PENDING_WRITES.sum());
            var rejected = sender.sendMessage(Unpooled.buffer().writeByte(2));
            channel.runPendingTasks();
            assertFalse(rejected.isSuccess());
            assertTrue(first.isDone());
            assertEquals(baseline, FlowControlMetrics.PENDING_WRITES.sum());
            assertEquals(bytes, FlowControlMetrics.PENDING_SOCKET_BYTES.sum());
            assertEquals(sources, FlowControlMetrics.PENDING_PAYLOAD_BYTES.sum());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Observes ownership of the serialized source while preserving normal HTTP response behavior.
     */
    private static final class CapturingMetricsHandler extends MetricsHandler {

        /**
         * Creates the temporary-buffer ownership observer.
         */
        private CapturingMetricsHandler() {}

        /**
         * Last temporary native source copied into the response.
         */
        private io.netty.buffer.ByteBuf temporary;

        /**
         * {@inheritDoc}
         */
        @Override
        protected void sendHttpResponse(io.netty.channel.ChannelHandlerContext ctx, io.netty.buffer.ByteBuf content, String type) {
            temporary = content;
            super.sendHttpResponse(ctx, content, type);
        }
    }

}
