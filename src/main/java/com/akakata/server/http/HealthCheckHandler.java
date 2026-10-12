package com.akakata.server.http;

import com.akakata.lifecycle.ServerLifecycle;
import com.akakata.metrics.ServerHealth;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Objects;

/**
 * Presents injected lifecycle and availability snapshots as HTTP health responses.
 *
 * <p>The handler never waits for a business executor or owns application resources.
 * JSON content is released after copying, including failed response construction.
 */
public class HealthCheckHandler extends AbstractHttpHandler {

    /**
     * Logger for health presentation failures.
     */
    private static final Logger LOG = LoggerFactory.getLogger(HealthCheckHandler.class);

    /**
     * Thread-safe serializer for immutable health snapshots.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Nonblocking provider selected by the composition root.
     */
    private final ServerHealth health;

    /**
     * Creates an unbound handler that fails readiness until a provider is supplied.
     */
    public HealthCheckHandler() {
        this(() -> new ServerHealth.Snapshot(ServerLifecycle.Phase.CREATED, false, false, Map.of()));
    }

    /**
     * Creates an HTTP presenter for an application's health provider.
     *
     * @param health nonblocking snapshot provider
     */
    public HealthCheckHandler(ServerHealth health) {
        this.health = Objects.requireNonNull(health, "health");
    }

    /**
     * Presents overall health, liveness or readiness with JSON on both success and failure.
     *
     * @param ctx     channel handler context
     * @param request borrowed request, including an optional query string
     */
    public void handleHealthCheck(ChannelHandlerContext ctx, FullHttpRequest request) {
        try {
            if (!request.decoderResult().isSuccess()) {
                sendError(ctx, HttpResponseStatus.BAD_REQUEST);
                return;
            }
            String path = new QueryStringDecoder(request.uri()).path();
            if (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            if (!path.equals("/health") && !path.equals("/health/live") && !path.equals("/health/ready")) {
                sendError(ctx, HttpResponseStatus.NOT_FOUND, true);
                return;
            }
            ServerHealth.Snapshot snapshot = health.snapshot();
            boolean available = path.equals("/health/live") ? snapshot.live() : snapshot.ready();
            String json = MAPPER.writeValueAsString(Map.of(
                    "status", available ? "UP" : "DOWN", "phase", snapshot.phase().name(),
                    "checks", snapshot.checks(), "timestamp", System.currentTimeMillis()));
            ByteBuf content = createContent(json);
            try {
                sendHttpResponse(ctx, content, "application/json; charset=UTF-8",
                        available ? HttpResponseStatus.OK : HttpResponseStatus.SERVICE_UNAVAILABLE);
            } finally {
                content.release();
            }
        } catch (RuntimeException failure) {
            LOG.error("Health presentation failed", failure);
            sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, true);
        }
    }

    /**
     * Creates owned JSON content; subclasses may select a pooled allocator.
     *
     * @param json response text
     * @return one owned buffer reference released by this handler after copying
     */
    protected ByteBuf createContent(String json) {
        return Unpooled.copiedBuffer(json, CharsetUtil.UTF_8);
    }
}
