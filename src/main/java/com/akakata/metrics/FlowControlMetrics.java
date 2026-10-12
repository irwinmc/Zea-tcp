package com.akakata.metrics;

import com.akakata.event.impl.EventDispatchers;
import com.akakata.event.impl.NettyEventDispatcher;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * Exposes live process-wide socket counters and Session/Game executor snapshots.
 * Counters are cumulative; gauges return to zero when owned channel resources close.
 */
public final class FlowControlMetrics {

    /**
     * Currently admitted TCP/WebSocket sockets including unauthenticated clients.
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
     * Socket writes rejected by count, byte or writable-state admission.
     */
    public static final LongAdder WRITE_REJECTIONS = new LongAdder();

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
        result.put("write_rejections", WRITE_REJECTIONS.sum());
        result.put("encoded_frames", ENCODED_FRAMES.sum());
        result.put("encoding_reuses", ENCODING_REUSES.sum());
        return result;
    }
}
