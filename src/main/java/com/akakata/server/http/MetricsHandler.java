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
 * Metrics endpoint handler.
 * Provides detailed server metrics in JSON or Prometheus format.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET /metrics - All metrics in JSON format</li>
 *   <li>GET /metrics/connections - Connection metrics</li>
 *   <li>GET /metrics/traffic - Traffic metrics</li>
 *   <li>GET /metrics/system - System metrics (CPU, memory, threads)</li>
 *   <li>GET /metrics/prometheus - Prometheus-compatible format</li>
 * </ul>
 *
 * @author Kelvin
 */
public class MetricsHandler extends AbstractHttpHandler {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsHandler.class);

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
        StringBuilder json = new StringBuilder();
        json.append("{\n");

        // Server info
        json.append("  \"server\": {\n");
        json.append("    \"uptime_seconds\": ").append(metrics.getUptimeSeconds()).append(",\n");
        json.append("    \"uptime_formatted\": \"").append(formatUptime(metrics.getUptimeSeconds())).append("\"\n");
        json.append("  },\n");

        // Connection metrics
        json.append("  \"connections\": {\n");
        json.append("    \"total\": ").append(metrics.getTotalConnections()).append(",\n");
        json.append("    \"active\": ").append(metrics.getActiveConnections()).append(",\n");
        json.append("    \"channels\": ").append(metrics.getCurrentChannelCount()).append("\n");
        json.append("  },\n");

        // Traffic metrics
        json.append("  \"traffic\": {\n");
        json.append("    \"messages_received\": ").append(metrics.getTotalMessagesReceived()).append(",\n");
        json.append("    \"messages_sent\": ").append(metrics.getTotalMessagesSent()).append(",\n");
        json.append("    \"bytes_received\": ").append(metrics.getTotalBytesReceived()).append(",\n");
        json.append("    \"bytes_sent\": ").append(metrics.getTotalBytesSent()).append(",\n");
        json.append("    \"bytes_received_mb\": ").append(metrics.getTotalBytesReceived() / 1024 / 1024).append(",\n");
        json.append("    \"bytes_sent_mb\": ").append(metrics.getTotalBytesSent() / 1024 / 1024).append("\n");
        json.append("  },\n");

        // System metrics
        json.append("  \"system\": {\n");
        json.append("    \"memory_used_mb\": ").append(metrics.getUsedMemoryMB()).append(",\n");
        json.append("    \"memory_max_mb\": ").append(metrics.getMaxMemoryMB()).append(",\n");
        json.append("    \"memory_usage_percent\": ").append((int) (metrics.getUsedMemoryMB() * 100.0 / metrics.getMaxMemoryMB())).append(",\n");
        json.append("    \"thread_count\": ").append(metrics.getThreadCount()).append(",\n");
        json.append("    \"cpu_load\": ").append(String.format("%.2f", metrics.getCpuLoad())).append("\n");
        json.append("  },\n");

        // Error metrics
        json.append("  \"errors\": {\n");
        json.append("    \"total\": ").append(metrics.getTotalErrors()).append("\n");
        json.append("  },\n");

        // Timestamp
        json.append("  \"timestamp\": ").append(System.currentTimeMillis()).append("\n");

        json.append("}");

        sendHttpResponse(ctx, Unpooled.copiedBuffer(json.toString(), CharsetUtil.UTF_8), "application/json; charset=UTF-8");
    }

    /**
     * Return connection metrics only.
     */
    private void handleConnectionMetrics(ChannelHandlerContext ctx) {
        String json = String.format(
                "{\n" +
                "  \"total_connections\": %d,\n" +
                "  \"active_connections\": %d,\n" +
                "  \"active_channels\": %d,\n" +
                "  \"timestamp\": %d\n" +
                "}",
                metrics.getTotalConnections(),
                metrics.getActiveConnections(),
                metrics.getCurrentChannelCount(),
                System.currentTimeMillis()
        );

        sendHttpResponse(ctx, Unpooled.copiedBuffer(json, CharsetUtil.UTF_8), "application/json; charset=UTF-8");
    }

    /**
     * Return traffic metrics only.
     */
    private void handleTrafficMetrics(ChannelHandlerContext ctx) {
        String json = String.format(
                "{\n" +
                "  \"messages_received\": %d,\n" +
                "  \"messages_sent\": %d,\n" +
                "  \"bytes_received\": %d,\n" +
                "  \"bytes_sent\": %d,\n" +
                "  \"bytes_received_mb\": %d,\n" +
                "  \"bytes_sent_mb\": %d,\n" +
                "  \"timestamp\": %d\n" +
                "}",
                metrics.getTotalMessagesReceived(),
                metrics.getTotalMessagesSent(),
                metrics.getTotalBytesReceived(),
                metrics.getTotalBytesSent(),
                metrics.getTotalBytesReceived() / 1024 / 1024,
                metrics.getTotalBytesSent() / 1024 / 1024,
                System.currentTimeMillis()
        );

        sendHttpResponse(ctx, Unpooled.copiedBuffer(json, CharsetUtil.UTF_8), "application/json; charset=UTF-8");
    }

    /**
     * Return system metrics only.
     */
    private void handleSystemMetrics(ChannelHandlerContext ctx) {
        String json = String.format(
                "{\n" +
                "  \"memory_used_mb\": %d,\n" +
                "  \"memory_max_mb\": %d,\n" +
                "  \"memory_usage_percent\": %d,\n" +
                "  \"thread_count\": %d,\n" +
                "  \"cpu_load\": %.2f,\n" +
                "  \"uptime_seconds\": %d,\n" +
                "  \"timestamp\": %d\n" +
                "}",
                metrics.getUsedMemoryMB(),
                metrics.getMaxMemoryMB(),
                (int) (metrics.getUsedMemoryMB() * 100.0 / metrics.getMaxMemoryMB()),
                metrics.getThreadCount(),
                metrics.getCpuLoad(),
                metrics.getUptimeSeconds(),
                System.currentTimeMillis()
        );

        sendHttpResponse(ctx, Unpooled.copiedBuffer(json, CharsetUtil.UTF_8), "application/json; charset=UTF-8");
    }

    /**
     * Return metrics in Prometheus format.
     * Compatible with Prometheus scraping.
     */
    private void handlePrometheusMetrics(ChannelHandlerContext ctx) {
        StringBuilder prometheus = new StringBuilder();

        // Server uptime
        prometheus.append("# HELP server_uptime_seconds Server uptime in seconds\n");
        prometheus.append("# TYPE server_uptime_seconds counter\n");
        prometheus.append("server_uptime_seconds ").append(metrics.getUptimeSeconds()).append("\n\n");

        // Connections
        prometheus.append("# HELP connections_total Total number of connections\n");
        prometheus.append("# TYPE connections_total counter\n");
        prometheus.append("connections_total ").append(metrics.getTotalConnections()).append("\n\n");

        prometheus.append("# HELP connections_active Current active connections\n");
        prometheus.append("# TYPE connections_active gauge\n");
        prometheus.append("connections_active ").append(metrics.getActiveConnections()).append("\n\n");

        prometheus.append("# HELP channels_active Current active channels\n");
        prometheus.append("# TYPE channels_active gauge\n");
        prometheus.append("channels_active ").append(metrics.getCurrentChannelCount()).append("\n\n");

        // Messages
        prometheus.append("# HELP messages_received_total Total messages received\n");
        prometheus.append("# TYPE messages_received_total counter\n");
        prometheus.append("messages_received_total ").append(metrics.getTotalMessagesReceived()).append("\n\n");

        prometheus.append("# HELP messages_sent_total Total messages sent\n");
        prometheus.append("# TYPE messages_sent_total counter\n");
        prometheus.append("messages_sent_total ").append(metrics.getTotalMessagesSent()).append("\n\n");

        // Bytes
        prometheus.append("# HELP bytes_received_total Total bytes received\n");
        prometheus.append("# TYPE bytes_received_total counter\n");
        prometheus.append("bytes_received_total ").append(metrics.getTotalBytesReceived()).append("\n\n");

        prometheus.append("# HELP bytes_sent_total Total bytes sent\n");
        prometheus.append("# TYPE bytes_sent_total counter\n");
        prometheus.append("bytes_sent_total ").append(metrics.getTotalBytesSent()).append("\n\n");

        // Memory
        prometheus.append("# HELP memory_used_bytes Memory used in bytes\n");
        prometheus.append("# TYPE memory_used_bytes gauge\n");
        prometheus.append("memory_used_bytes ").append(metrics.getUsedMemoryMB() * 1024 * 1024).append("\n\n");

        prometheus.append("# HELP memory_max_bytes Maximum memory in bytes\n");
        prometheus.append("# TYPE memory_max_bytes gauge\n");
        prometheus.append("memory_max_bytes ").append(metrics.getMaxMemoryMB() * 1024 * 1024).append("\n\n");

        // Threads
        prometheus.append("# HELP threads_current Current thread count\n");
        prometheus.append("# TYPE threads_current gauge\n");
        prometheus.append("threads_current ").append(metrics.getThreadCount()).append("\n\n");

        // CPU
        prometheus.append("# HELP cpu_load System CPU load\n");
        prometheus.append("# TYPE cpu_load gauge\n");
        prometheus.append("cpu_load ").append(String.format("%.2f", metrics.getCpuLoad())).append("\n\n");

        // Errors
        prometheus.append("# HELP errors_total Total errors\n");
        prometheus.append("# TYPE errors_total counter\n");
        prometheus.append("errors_total ").append(metrics.getTotalErrors()).append("\n");

        sendHttpResponse(ctx, Unpooled.copiedBuffer(prometheus.toString(), CharsetUtil.UTF_8), "text/plain; charset=UTF-8");
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
