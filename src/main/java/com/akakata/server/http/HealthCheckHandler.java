package com.akakata.server.http;

import com.akakata.metrics.ServerMetrics;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Health check endpoint handler.
 * Provides simple UP/DOWN status for load balancers and monitoring systems.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /health - Simple health check (returns "OK")</li>
 *   <li>GET /health/live - Liveness probe (is server running?)</li>
 *   <li>GET /health/ready - Readiness probe (is server ready to accept traffic?)</li>
 * </ul>
 *
 * @author Kelvin
 */
public class HealthCheckHandler extends AbstractHttpHandler {

    private static final Logger LOG = LoggerFactory.getLogger(HealthCheckHandler.class);

    private final ServerMetrics metrics = ServerMetrics.getInstance();

    /**
     * Handle health check request.
     *
     * @param ctx     channel handler context
     * @param request HTTP request
     */
    public void handleHealthCheck(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();

        try {
            if (uri.equals("/health") || uri.equals("/health/")) {
                handleBasicHealth(ctx);
            } else if (uri.startsWith("/health/live")) {
                handleLivenessProbe(ctx);
            } else if (uri.startsWith("/health/ready")) {
                handleReadinessProbe(ctx);
            } else {
                sendError(ctx, HttpResponseStatus.NOT_FOUND, true);
            }
        } catch (Exception e) {
            LOG.error("Error handling health check", e);
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, true);
        }
    }

    /**
     * Basic health check - always returns OK if server is running.
     */
    private void handleBasicHealth(ChannelHandlerContext ctx) {
        String response = buildJsonResponse("OK", "Server is running");
        sendHttpResponse(ctx, Unpooled.copiedBuffer(response, CharsetUtil.UTF_8), "application/json; charset=UTF-8");
    }

    /**
     * Liveness probe - checks if server is alive.
     * Used by orchestrators (K8s) to restart unhealthy containers.
     */
    private void handleLivenessProbe(ChannelHandlerContext ctx) {
        // Check if server is responsive
        boolean alive = isServerAlive();

        if (alive) {
            String response = buildJsonResponse("UP", "Server is alive");
            sendHttpResponse(ctx, Unpooled.copiedBuffer(response, CharsetUtil.UTF_8), "application/json; charset=UTF-8");
        } else {
            sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, true);
        }
    }

    /**
     * Readiness probe - checks if server is ready to accept traffic.
     * Used by load balancers to route traffic only to ready instances.
     */
    private void handleReadinessProbe(ChannelHandlerContext ctx) {
        boolean ready = isServerReady();

        if (ready) {
            String response = buildJsonResponse("UP", "Server is ready");
            sendHttpResponse(ctx, Unpooled.copiedBuffer(response, CharsetUtil.UTF_8), "application/json; charset=UTF-8");
        } else {
            String response = buildJsonResponse("DOWN", "Server is not ready");
            sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, true);
        }
    }

    /**
     * Check if server is alive (basic checks).
     */
    private boolean isServerAlive() {
        try {
            // Check if we can access metrics
            long uptime = metrics.getUptimeSeconds();
            return uptime > 0;
        } catch (Exception e) {
            LOG.error("Liveness check failed", e);
            return false;
        }
    }

    /**
     * Check if server is ready to accept traffic.
     */
    private boolean isServerReady() {
        try {
            // Check memory availability
            long usedMem = metrics.getUsedMemoryMB();
            long maxMem = metrics.getMaxMemoryMB();
            double memoryUsage = (double) usedMem / maxMem;

            if (memoryUsage > 0.95) {
                LOG.warn("Memory usage too high: {}%", (int) (memoryUsage * 100));
                return false;
            }

            // Check error rate
            long totalMessages = metrics.getTotalMessagesReceived();
            long totalErrors = metrics.getTotalErrors();
            if (totalMessages > 100) {
                double errorRate = (double) totalErrors / totalMessages;
                if (errorRate > 0.5) {
                    LOG.warn("Error rate too high: {}%", (int) (errorRate * 100));
                    return false;
                }
            }

            // Check if at least one channel is active
            int channelCount = metrics.getCurrentChannelCount();
            // Server is ready even with 0 channels (waiting for connections)

            return true;
        } catch (Exception e) {
            LOG.error("Readiness check failed", e);
            return false;
        }
    }

    /**
     * Build JSON response.
     */
    private String buildJsonResponse(String status, String message) {
        return String.format(
                "{\"status\":\"%s\",\"message\":\"%s\",\"timestamp\":%d}",
                status,
                message,
                System.currentTimeMillis()
        );
    }
}
