package com.akakata.server.http;

import com.akakata.metrics.EventDispatcherMetrics;
import com.akakata.metrics.ServerMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Metrics endpoint handler.
 * Provides detailed server metrics in JSON or Prometheus format.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /metrics - All metrics in JSON format</li>
 *   <li>GET /metrics/connections - Connection metrics</li>
 *   <li>GET /metrics/traffic - Traffic metrics</li>
 *   <li>GET /metrics/system - System metrics (CPU, memory, threads)</li>
 *   <li>GET /metrics/event-dispatcher - Event dispatcher queue metrics</li>
 *   <li>GET /metrics/prometheus - Prometheus-compatible format</li>
 * </ul>
 *
 * @author Kelvin
 */
public class MetricsHandler extends AbstractHttpHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ServerMetrics metrics = ServerMetrics.getInstance();

    /**
     * Handle metrics request.
     *
     * @param ctx     channel handler context
     * @param request HTTP request
     */
    public void handleMetrics(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();

        try {
            if (uri.equals("/metrics") || uri.equals("/metrics/")) {
                handleAllMetrics(ctx);
            } else if (uri.startsWith("/metrics/connections")) {
                handleConnectionMetrics(ctx);
            } else if (uri.startsWith("/metrics/traffic")) {
                handleTrafficMetrics(ctx);
            } else if (uri.startsWith("/metrics/system")) {
                handleSystemMetrics(ctx);
            } else if (uri.startsWith("/metrics/event-dispatcher")) {
                handleEventDispatcherMetrics(ctx);
            } else if (uri.startsWith("/metrics/prometheus")) {
                handlePrometheusMetrics(ctx);
            } else {
                sendError(ctx, HttpResponseStatus.NOT_FOUND, true);
            }
        } catch (Exception e) {
            LOG.error("Error handling metrics request", e);
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, true);
        }
    }

    /**
     * Return all metrics in JSON format.
     */
    private void handleAllMetrics(ChannelHandlerContext ctx) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();

            // Server info
            Map<String, Object> server = new LinkedHashMap<>();
            server.put("uptime_seconds", metrics.getUptimeSeconds());
            server.put("uptime_formatted", formatUptime(metrics.getUptimeSeconds()));
            response.put("server", server);

            // Connection metrics
            Map<String, Object> connections = new LinkedHashMap<>();
            connections.put("total", metrics.getTotalConnections());
            connections.put("active", metrics.getActiveConnections());
            connections.put("channels", metrics.getCurrentChannelCount());
            response.put("connections", connections);

            // Traffic metrics
            Map<String, Object> traffic = new LinkedHashMap<>();
            traffic.put("messages_received", metrics.getTotalMessagesReceived());
            traffic.put("messages_sent", metrics.getTotalMessagesSent());
            traffic.put("bytes_received", metrics.getTotalBytesReceived());
            traffic.put("bytes_sent", metrics.getTotalBytesSent());
            traffic.put("bytes_received_mb", metrics.getTotalBytesReceived() / 1024 / 1024);
            traffic.put("bytes_sent_mb", metrics.getTotalBytesSent() / 1024 / 1024);
            response.put("traffic", traffic);

            // System metrics
            Map<String, Object> system = new LinkedHashMap<>();
            system.put("memory_used_mb", metrics.getUsedMemoryMB());
            system.put("memory_max_mb", metrics.getMaxMemoryMB());
            system.put("memory_usage_percent", (int) (metrics.getUsedMemoryMB() * 100.0 / metrics.getMaxMemoryMB()));
            system.put("thread_count", metrics.getThreadCount());
            system.put("cpu_load", Double.parseDouble(String.format("%.2f", metrics.getCpuLoad())));
            response.put("system", system);

            // Error metrics
            Map<String, Object> errors = new LinkedHashMap<>();
            errors.put("total", metrics.getTotalErrors());
            response.put("errors", errors);

            // Timestamp
            response.put("timestamp", System.currentTimeMillis());

            String json = MAPPER.writeValueAsString(response);
            sendHttpResponse(ctx, Unpooled.copiedBuffer(json, CharsetUtil.UTF_8), "application/json; charset=UTF-8");
        } catch (Exception e) {
            LOG.error("Failed to serialize all metrics", e);
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, true);
        }
    }

    /**
     * Return connection metrics only.
     */
    private void handleConnectionMetrics(ChannelHandlerContext ctx) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("total_connections", metrics.getTotalConnections());
            response.put("active_connections", metrics.getActiveConnections());
            response.put("active_channels", metrics.getCurrentChannelCount());
            response.put("timestamp", System.currentTimeMillis());

            String json = MAPPER.writeValueAsString(response);
            sendHttpResponse(ctx, Unpooled.copiedBuffer(json, CharsetUtil.UTF_8), "application/json; charset=UTF-8");
        } catch (Exception e) {
            LOG.error("Failed to serialize connection metrics", e);
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, true);
        }
    }

    /**
     * Return traffic metrics only.
     */
    private void handleTrafficMetrics(ChannelHandlerContext ctx) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("messages_received", metrics.getTotalMessagesReceived());
            response.put("messages_sent", metrics.getTotalMessagesSent());
            response.put("bytes_received", metrics.getTotalBytesReceived());
            response.put("bytes_sent", metrics.getTotalBytesSent());
            response.put("bytes_received_mb", metrics.getTotalBytesReceived() / 1024 / 1024);
            response.put("bytes_sent_mb", metrics.getTotalBytesSent() / 1024 / 1024);
            response.put("timestamp", System.currentTimeMillis());

            String json = MAPPER.writeValueAsString(response);
            sendHttpResponse(ctx, Unpooled.copiedBuffer(json, CharsetUtil.UTF_8), "application/json; charset=UTF-8");
        } catch (Exception e) {
            LOG.error("Failed to serialize traffic metrics", e);
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, true);
        }
    }

    /**
     * Return system metrics only.
     */
    private void handleSystemMetrics(ChannelHandlerContext ctx) {
        try {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("memory_used_mb", metrics.getUsedMemoryMB());
            response.put("memory_max_mb", metrics.getMaxMemoryMB());
            response.put("memory_usage_percent", (int) (metrics.getUsedMemoryMB() * 100.0 / metrics.getMaxMemoryMB()));
            response.put("thread_count", metrics.getThreadCount());
            response.put("cpu_load", Double.parseDouble(String.format("%.2f", metrics.getCpuLoad())));
            response.put("uptime_seconds", metrics.getUptimeSeconds());
            response.put("timestamp", System.currentTimeMillis());

            String json = MAPPER.writeValueAsString(response);
            sendHttpResponse(ctx, Unpooled.copiedBuffer(json, CharsetUtil.UTF_8), "application/json; charset=UTF-8");
        } catch (Exception e) {
            LOG.error("Failed to serialize system metrics", e);
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, true);
        }
    }

    /**
     * Return event dispatcher queue metrics.
     */
    private void handleEventDispatcherMetrics(ChannelHandlerContext ctx) {
        try {
            EventDispatcherMetrics edMetrics = EventDispatcherMetrics.getInstance();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("total_queue_size", edMetrics.getTotalQueueSize());
            response.put("per_shard_queue_sizes", edMetrics.getPerShardQueueSizes());
            response.put("shard_count", edMetrics.getShardCount());
            response.put("max_shard_queue_size", edMetrics.getMaxShardQueueSize());
            response.put("min_shard_queue_size", edMetrics.getMinShardQueueSize());
            response.put("average_shard_queue_size", edMetrics.getAverageShardQueueSize());
            response.put("last_update_timestamp", edMetrics.getLastUpdateTimestamp());
            response.put("is_started", edMetrics.isStarted());
            response.put("timestamp", System.currentTimeMillis());

            String json = MAPPER.writeValueAsString(response);
            sendHttpResponse(ctx, Unpooled.copiedBuffer(json, CharsetUtil.UTF_8), "application/json; charset=UTF-8");
        } catch (Exception e) {
            LOG.error("Failed to serialize event dispatcher metrics", e);
            sendError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, true);
        }
    }

    /**
     * Return metrics in Prometheus format.
     * Compatible with Prometheus scraping.
     */
    private void handlePrometheusMetrics(ChannelHandlerContext ctx) {
        StringBuilder prom = new StringBuilder();

        // Server uptime
        addPrometheusMetric(prom, "server_uptime_seconds", "counter", "Server uptime in seconds", metrics.getUptimeSeconds());

        // Connections
        addPrometheusMetric(prom, "connections_total", "counter", "Total number of connections", metrics.getTotalConnections());
        addPrometheusMetric(prom, "connections_active", "gauge", "Current active connections", metrics.getActiveConnections());
        addPrometheusMetric(prom, "channels_active", "gauge", "Current active channels", metrics.getCurrentChannelCount());

        // Messages
        addPrometheusMetric(prom, "messages_received_total", "counter", "Total messages received", metrics.getTotalMessagesReceived());
        addPrometheusMetric(prom, "messages_sent_total", "counter", "Total messages sent", metrics.getTotalMessagesSent());

        // Bytes
        addPrometheusMetric(prom, "bytes_received_total", "counter", "Total bytes received", metrics.getTotalBytesReceived());
        addPrometheusMetric(prom, "bytes_sent_total", "counter", "Total bytes sent", metrics.getTotalBytesSent());

        // Memory
        addPrometheusMetric(prom, "memory_used_bytes", "gauge", "Memory used in bytes", metrics.getUsedMemoryMB() * 1024 * 1024);
        addPrometheusMetric(prom, "memory_max_bytes", "gauge", "Maximum memory in bytes", metrics.getMaxMemoryMB() * 1024 * 1024);

        // Threads
        addPrometheusMetric(prom, "threads_current", "gauge", "Current thread count", metrics.getThreadCount());

        // CPU
        addPrometheusMetric(prom, "cpu_load", "gauge", "System CPU load", Double.parseDouble(String.format("%.2f", metrics.getCpuLoad())));

        // Errors
        addPrometheusMetric(prom, "errors_total", "counter", "Total errors", metrics.getTotalErrors());

        sendHttpResponse(ctx, Unpooled.copiedBuffer(prom.toString(), CharsetUtil.UTF_8), "text/plain; charset=UTF-8");
    }

    /**
     * Add a Prometheus metric entry.
     *
     * @param sb    StringBuilder to append to
     * @param name  metric name
     * @param type  metric type (counter, gauge, histogram, summary)
     * @param help  help description
     * @param value metric value
     */
    private void addPrometheusMetric(StringBuilder sb, String name, String type, String help, Object value) {
        sb.append("# HELP ").append(name).append(" ").append(help).append("\n");
        sb.append("# TYPE ").append(name).append(" ").append(type).append("\n");
        sb.append(name).append(" ").append(value).append("\n\n");
    }

    /**
     * Format uptime in human-readable format.
     */
    private String formatUptime(long seconds) {
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (days > 0) {
            return String.format("%dd %dh %dm %ds", days, hours, minutes, secs);
        } else if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, secs);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, secs);
        } else {
            return String.format("%ds", secs);
        }
    }
}
