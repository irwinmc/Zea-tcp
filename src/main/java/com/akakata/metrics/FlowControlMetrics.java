package com.akakata.metrics;

import com.akakata.event.impl.EventDispatchers;
import com.akakata.event.impl.NettyEventDispatcher;
import com.akakata.communication.OutboundBudget;
import com.akakata.service.task.BlockingTaskExecutor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Exposes live process-wide socket counters and Session/Game executor snapshots.
 * Counters are cumulative; gauges return to zero when owned channel resources close.
 */
public final class FlowControlMetrics {

    /**
     * Currently admitted TCP, WebSocket and HTTP sockets including unauthenticated clients.
     */
    public static final LongAdder ACTIVE_CONNECTIONS = new LongAdder();

    /**
     * Socket admissions rejected by the context connection budget.
     */
    public static final LongAdder CONNECTION_REJECTIONS = new LongAdder();

    /**
     * Connections closed by their authentication deadline.
     */
    public static final LongAdder LOGIN_TIMEOUTS = new LongAdder();

    /**
     * Connections closed after read inactivity.
     */
    public static final LongAdder IDLE_TIMEOUTS = new LongAdder();

    /**
     * Channels whose reads are currently paused by business or socket pressure.
     */
    public static final LongAdder PAUSED_CHANNELS = new LongAdder();

    /**
     * Cumulative read pauses started by business or socket pressure.
     */
    public static final LongAdder READ_PAUSES = new LongAdder();

    /**
     * Connections closed after sustained pressure exceeded the read-pause deadline.
     */
    public static final LongAdder PAUSE_TIMEOUTS = new LongAdder();

    /**
     * Application socket writes awaiting promise completion.
     */
    public static final LongAdder PENDING_WRITES = new LongAdder();

    /**
     * Encoded socket bytes awaiting promise completion.
     */
    public static final LongAdder PENDING_SOCKET_BYTES = new LongAdder();

    /**
     * Estimated source bytes held by application writes before transport completion.
     */
    public static final LongAdder PENDING_PAYLOAD_BYTES = new LongAdder();

    /**
     * HTTP connections closed by their absolute request deadline.
     */
    public static final LongAdder HTTP_TIMEOUTS = new LongAdder();

    /**
     * Reserved HTTP file transfers, including opening files and active responses.
     */
    public static final LongAdder PENDING_HTTP_FILE_TRANSFERS = new LongAdder();

    /**
     * HTTP file requests rejected before opening a handle because admission failed.
     */
    public static final LongAdder HTTP_FILE_TRANSFER_REJECTIONS = new LongAdder();

    /**
     * Connections closed by an independent absolute file-response deadline.
     */
    public static final LongAdder HTTP_FILE_TRANSFER_TIMEOUTS = new LongAdder();

    /**
     * Socket writes rejected by count or byte admission.
     */
    public static final LongAdder WRITE_REJECTIONS = new LongAdder();

    /**
     * Connections closed because an admitted socket write exceeded its completion deadline.
     */
    public static final LongAdder WRITE_TIMEOUTS = new LongAdder();

    /**
     * Actual application-frame encodings, including cached fan-out frames.
     */
    public static final LongAdder ENCODED_FRAMES = new LongAdder();

    /**
     * Recipient encodings served from an existing shared application frame.
     */
    public static final LongAdder ENCODING_REUSES = new LongAdder();

    /**
     * Game queue sampled by the current context; cleared at context closure.
     */
    private static volatile NettyEventDispatcher game;

    /**
     * Current context's aggregate write and blocking-task owners, or null after closure.
     */
    private static volatile ResourceOwners resources;

    /**
     * Captures resource owners together for coherent configuration sampling.
     *
     * @param outbound the aggregate write owner
     * @param blocking the bounded slow-operation owner
     */
    private record ResourceOwners(OutboundBudget outbound, BlockingTaskExecutor blocking) {
    }

    /**
     * Prevents construction of this metrics utility.
     */
    private FlowControlMetrics() {
    }

    /**
     * Registers a context-owned Game for live sampling.
     *
     * @param dispatcher the Game executor, or null to clear ownership
     */
    public static void gameDispatcher(NettyEventDispatcher dispatcher) {
        game = dispatcher;
    }

    /**
     * Registers context-wide resource owners or clears them after closure.
     *
     * @param outbound the aggregate write owner, or null to clear both references
     * @param blocking the bounded slow-operation owner
     */
    public static void resources(OutboundBudget outbound, BlockingTaskExecutor blocking) {
        resources = outbound == null ? null : new ResourceOwners(outbound, java.util.Objects.requireNonNull(blocking));
    }

    /**
     * Captures current Session/Game budgets, outcomes, timings and socket counters.
     *
     * @return JSON-compatible metrics grouped by executor and transport
     */
    public static Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("session", EventDispatchers.sharedGroup().snapshots());
        NettyEventDispatcher current = game;
        result.put("game", current == null ? null : current.snapshot());
        result.put("transport", transportSnapshot());
        ResourceOwners owners = resources;
        result.put("outbound", owners == null ? null : Map.of("byte_budget", owners.outbound.maximumBytes(),
                "pending_bytes", owners.outbound.pendingBytes()));
        result.put("blocking", owners == null ? null : owners.blocking.snapshot());
        return result;
    }

    /**
     * Captures scalar transport gauges and cumulative rejection/timeout counters.
     *
     * @return transport metric names mapped to current values
     */
    public static Map<String, Long> transportSnapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("active_connections", ACTIVE_CONNECTIONS.sum());
        result.put("connection_rejections", CONNECTION_REJECTIONS.sum());
        result.put("login_timeouts", LOGIN_TIMEOUTS.sum());
        result.put("idle_timeouts", IDLE_TIMEOUTS.sum());
        result.put("paused_channels", PAUSED_CHANNELS.sum());
        result.put("read_pauses", READ_PAUSES.sum());
        result.put("pause_timeouts", PAUSE_TIMEOUTS.sum());
        result.put("pending_writes", PENDING_WRITES.sum());
        result.put("pending_socket_bytes", PENDING_SOCKET_BYTES.sum());
        result.put("pending_payload_bytes", PENDING_PAYLOAD_BYTES.sum());
        result.put("http_timeouts", HTTP_TIMEOUTS.sum());
        result.put("pending_http_file_transfers", PENDING_HTTP_FILE_TRANSFERS.sum());
        result.put("http_file_transfer_rejections", HTTP_FILE_TRANSFER_REJECTIONS.sum());
        result.put("http_file_transfer_timeouts", HTTP_FILE_TRANSFER_TIMEOUTS.sum());
        result.put("write_rejections", WRITE_REJECTIONS.sum());
        result.put("write_timeouts", WRITE_TIMEOUTS.sum());
        result.put("encoded_frames", ENCODED_FRAMES.sum());
        result.put("encoding_reuses", ENCODING_REUSES.sum());
        return result;
    }
}
