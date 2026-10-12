package com.akakata.benchmark;

import com.akakata.app.Game;
import com.akakata.context.ServerContext;
import com.akakata.event.Event;
import com.akakata.event.EventHandler;
import com.akakata.event.Events;
import com.akakata.event.impl.PlayerMessageEvent;
import com.akakata.metrics.FlowControlMetrics;
import com.akakata.server.ServerManager;
import com.akakata.server.impl.AbstractNettyServer;
import io.netty.channel.ServerChannel;
import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * Runs real TCP or WebSocket clients against the production login, codec and Game pipelines.
 *
 * <p>The optional embedded server binds an ephemeral port and executes an explicit
 * ordered echo Game handler. An external host and port must provide the same echo
 * behavior. Clients verify every response sequence, use bounded outstanding windows
 * and report end-to-end P50/P95/P99 latency, throughput and failed requests. Slow-read
 * latency intentionally includes the configured client delay. Results describe this
 * workload; they do not establish production capacity for other business handlers.
 */
public final class NetworkLoadTest {

    /**
     * Shared thread-safe mapper for client request and response objects.
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Prevents construction of this command-line driver.
     */
    private NetworkLoadTest() {
    }

    /**
     * Runs one transport workload or the isolated real-network regression probe.
     *
     * @param arguments options in {@code --name=value} form; {@code --verify} runs regressions
     * @throws Exception if setup, a bounded client task or regression verification fails
     */
    public static void main(String[] arguments) throws Exception {
        Map<String, String> options = new HashMap<>();
        for (String argument : arguments) {
            String[] parts = argument.replaceFirst("^--", "").split("=", 2);
            options.put(parts[0], parts.length == 1 ? "true" : parts[1]);
        }
        boolean websocket = options.getOrDefault("transport", "tcp").equals("websocket");
        if (!Set.of("tcp", "websocket").contains(options.getOrDefault("transport", "tcp"))) {
            throw new IllegalArgumentException("Transport must be tcp or websocket");
        }
        boolean verify = options.containsKey("verify");
        int port = number(options, "port", 0);
        String host = options.getOrDefault("host", "127.0.0.1");
        ServerContext context = null;
        try {
            if (port == 0) {
                context = new ServerContext();
                var properties = context.getConfigManager().getProperties();
                properties.setProperty("security.allow.anonymous", "true");
                properties.setProperty("server.tcp.enabled", Boolean.toString(!websocket));
                properties.setProperty("server.websocket.enabled", Boolean.toString(websocket));
                properties.setProperty("server.http.enabled", "false");
                properties.setProperty("tcp.port", "0");
                properties.setProperty("web.socket.port", "0");
                properties.setProperty("metrics.event.interval.seconds", "0");
                properties.setProperty("bossThreadCount", "1");
                properties.setProperty("workerThreadCount", "2");
                properties.setProperty("session.dispatcher.shards", "2");
                properties.setProperty("socket.login.timeout.millis", verify ? "500" : "30000");
                properties.setProperty("socket.read.idle.millis", verify ? "1500" : "120000");
                properties.setProperty("socket.maximum.pending.bytes", options.getOrDefault("socket-bytes", "1048576"));
                context.initialize();
                Game game = context.getBean(Game.class);
                int workMicros = number(options, "business-micros", 0);
                game.getEventDispatcher().addHandler(new EventHandler() {
                    @Override
                    public int getEventType() {
                        return Events.SESSION_MESSAGE;
                    }

                    @Override
                    public void onEvent(Event event) {
                        if (workMicros > 0) {
                            java.util.concurrent.locks.LockSupport.parkNanos(workMicros * 1_000L);
                        }
                        PlayerMessageEvent message = (PlayerMessageEvent) event;
                        game.sendToPlayer(Events.networkEvent(message.getSource()), message.getPlayerSession())
                                .whenComplete((report, error) -> {
                                    if (error != null) {
                                        message.getPlayerSession().close();
                                    }
                                });
                    }
                });
                context.getBean(ServerManager.class).startServers();
                port = ((java.net.InetSocketAddress) AbstractNettyServer.ALL_CHANNELS.stream()
                        .filter(channel -> channel instanceof ServerChannel).findFirst().orElseThrow().localAddress()).getPort();
            }
            if (verify) {
                verifyNetwork(host, port, websocket);
            }
            else {
                runLoad(host, port, websocket, options);
            }
            if (context != null) {
                System.out.println("FLOW " + MAPPER.writeValueAsString(FlowControlMetrics.snapshot()));
            }
        } finally {
            if (context != null) {
                context.close();
                if (verify && (FlowControlMetrics.ACTIVE_CONNECTIONS.sum() != 0
                        || FlowControlMetrics.PENDING_WRITES.sum() != 0
                        || FlowControlMetrics.PENDING_SOCKET_BYTES.sum() != 0
                        || FlowControlMetrics.PENDING_PAYLOAD_BYTES.sum() != 0
                        || context.getBean(com.akakata.communication.OutboundBudget.class).pendingBytes() != 0
                        || FlowControlMetrics.PAUSED_CHANNELS.sum() != 0
                        || context.getBean(com.akakata.service.SessionService.class).size() != 0
                        || !context.getBean(Game.class).getSessions().isEmpty()
                        || com.akakata.event.impl.EventDispatchers.sharedGroup().snapshots().stream()
                        .anyMatch(snapshot -> snapshot.pendingBytes() != 0))) {
                    throw new IllegalStateException("Transport or session resources survived context shutdown");
                }
            }
        }
    }

    /**
     * Runs bounded virtual clients and computes exact observed latency percentiles.
     *
     * @param host      target hostname
     * @param port      target listening port
     * @param websocket whether to use WebSocket framing
     * @param options   workload configuration
     * @throws Exception if client completion exceeds the workload deadline
     */
    private static void runLoad(String host, int port, boolean websocket, Map<String, String> options) throws Exception {
        int clients = number(options, "clients", 16), messages = number(options, "messages", 1000);
        int window = number(options, "window", 16), payloadBytes = number(options, "payload-bytes", 128);
        int readDelay = number(options, "slow-read-millis", 0), timeout = number(options, "timeout-seconds", 60);
        if (clients <= 0 || messages <= 0 || window <= 0 || payloadBytes < 0 || payloadBytes > 60000
                || readDelay < 0 || timeout <= 0) {
            throw new IllegalArgumentException("Invalid workload limits");
        }
        String payload = "x".repeat(payloadBytes);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        LongAdder failures = new LongAdder(), completed = new LongAdder();
        long started = System.nanoTime();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> tasks = new ArrayList<>();
            for (int client = 0; client < clients; client++) {
                tasks.add(executor.submit(() -> {
                    int received = 0;
                    try (WireClient connection = new WireClient(host, port, websocket)) {
                        connection.login();
                        for (int base = 0; base < messages; base += window) {
                            int count = Math.min(window, messages - base);
                            long[] submitted = new long[count];
                            for (int offset = 0; offset < count; offset++) {
                                submitted[offset] = System.nanoTime();
                                connection.send(applicationFrame(Events.SESSION_MESSAGE, Map.of("sequence", base + offset, "payload", payload)));
                            }
                            for (int offset = 0; offset < count; offset++) {
                                if (readDelay > 0) {
                                    Thread.sleep(readDelay);
                                }
                                requireSequence(connection.read(), base + offset);
                                latencies.add(System.nanoTime() - submitted[offset]);
                                received++;
                                completed.increment();
                            }
                        }
                    } catch (Exception failure) {
                        failures.add(messages - received);
                        System.err.println("Client stopped after " + received + " replies: " + failure.getClass().getSimpleName());
                    }
                }));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
            try {
                for (Future<?> task : tasks) {
                    task.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                }
            } finally {
                tasks.forEach(task -> task.cancel(true));
            }
        }
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        long[] observed = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        System.out.printf(Locale.ROOT,
                "RESULT transport=%s clients=%d completed=%d failed=%d elapsed_seconds=%.3f replies_per_second=%.1f p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f%n",
                websocket ? "websocket" : "tcp", clients, completed.sum(), failures.sum(), seconds,
                completed.sum() / seconds, quantile(observed, .50), quantile(observed, .95), quantile(observed, .99));
    }

    /**
     * Verifies actual authentication transition, FIFO replies and hostile input cleanup.
     *
     * @param host      target hostname
     * @param port      target port
     * @param websocket selected transport
     * @throws Exception if a wire contract or connection deadline is violated
     */
    private static void verifyNetwork(String host, int port, boolean websocket) throws Exception {
        try (WireClient client = new WireClient(host, port, websocket)) {
            client.login();
            // Cross the login deadline; the authenticated connection must survive it.
            Thread.sleep(650);
            for (int sequence = 0; sequence < 32; sequence++) {
                client.send(applicationFrame(Events.SESSION_MESSAGE, Map.of("sequence", sequence)));
            }
            for (int sequence = 0; sequence < 32; sequence++) {
                requireSequence(client.read(), sequence);
            }
            client.send(applicationFrame(websocket ? Events.LOG_IN_SUCCESS : Events.NETWORK_MESSAGE, Map.of("sequence", 32)));
            client.requireClosed();
        }
        try (WireClient client = new WireClient(host, port, websocket)) {
            client.login();
            client.send(new byte[]{Events.SESSION_MESSAGE, 0, 0, 0, 30, '{', '}'});
            client.requireClosed();
        }
        try (WireClient unauthenticated = new WireClient(host, port, websocket)) {
            unauthenticated.requireClosed();
        }
        try (WireClient idle = new WireClient(host, port, websocket)) {
            idle.login();
            idle.requireClosed();
        }
        // A response larger than the encoded-byte budget must fail and close the real socket.
        try (WireClient oversized = new WireClient(host, port, websocket)) {
            oversized.login();
            oversized.send(applicationFrame(Events.SESSION_MESSAGE, Map.of("sequence", 0, "payload", "x".repeat(4096))));
            oversized.requireClosed();
        }
        System.out.println("NETWORK_REGRESSION_OK " + (websocket ? "websocket" : "tcp"));
    }

    /**
     * Encodes a JSON application frame with the current big-endian inner length field.
     *
     * @param opcode event opcode
     * @param source JSON object payload
     * @return application bytes excluding transport headers
     * @throws IOException if writing the in-memory frame fails
     */
    private static byte[] applicationFrame(int opcode, Object source) throws IOException {
        byte[] json = MAPPER.writeValueAsBytes(source);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(bytes);
        output.writeByte(opcode);
        output.writeInt(json.length);
        output.write(json);
        return bytes.toByteArray();
    }

    /**
     * Validates one ordered echo response independently of server-side admission counters.
     *
     * @param frame    received application bytes
     * @param expected expected sequence number
     * @throws IOException if the response shape, size or sequence is invalid
     */
    private static void requireSequence(byte[] frame, int expected) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(frame));
        if (input.readUnsignedByte() != Events.NETWORK_MESSAGE) {
            throw new IOException("Unexpected response opcode");
        }
        int bytes = input.readInt();
        if (bytes != input.available()) {
            throw new IOException("Invalid response length");
        }
        var object = MAPPER.readValue(input.readNBytes(bytes), Map.class);
        if (!(object.get("sequence") instanceof Number sequence) || sequence.intValue() != expected) {
            throw new IOException("Response sequence mismatch: " + object);
        }
    }

    /**
     * Reads an integer option using a documented default.
     *
     * @param options  parsed command-line arguments
     * @param name     option name
     * @param fallback default integer
     * @return parsed value
     */
    private static int number(Map<String, String> options, String name, int fallback) {
        return Integer.parseInt(options.getOrDefault(name, Integer.toString(fallback)));
    }

    /**
     * Computes a nearest-rank percentile over sorted end-to-end observations.
     *
     * @param nanos    sorted observed durations
     * @param quantile requested fractional percentile
     * @return observed latency in milliseconds, or zero when no replies completed
     */
    private static double quantile(long[] nanos, double quantile) {
        return nanos.length == 0 ? 0 : nanos[Math.max(0, (int) Math.ceil(nanos.length * quantile) - 1)] / 1_000_000.0;
    }

    /**
     * Implements bounded blocking TCP and masked binary WebSocket client framing.
     * Virtual-thread interruption closes blocking socket operations during cancellation.
     */
    private static final class WireClient implements AutoCloseable {

        /**
         * Owned client socket and transport-specific framing selection.
         */
        private final Socket socket = new Socket();

        /**
         * Whether this connection uses WebSocket rather than a TCP length prefix.
         */
        private final boolean websocket;

        /**
         * Blocking input and output streams owned by this socket.
         */
        private final DataInputStream input;

        /**
         * Outbound wire stream; application frames flush on each submission.
         */
        private final DataOutputStream output;

        /**
         * Connects a socket and performs the WebSocket upgrade when selected.
         *
         * @param host      target hostname
         * @param port      listening port
         * @param websocket transport selection
         * @throws IOException if connection or upgrade validation fails
         */
        private WireClient(String host, int port, boolean websocket) throws IOException {
            this.websocket = websocket;
            try {
                socket.connect(new java.net.InetSocketAddress(host, port), 5000);
                socket.setSoTimeout(5000);
                socket.setTcpNoDelay(true);
                socket.setReceiveBufferSize(8192);
                input = new DataInputStream(socket.getInputStream());
                output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 65536));
                if (websocket) {
                    output.write(("GET / HTTP/1.1\r\nHost: " + host + ":" + port
                            + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13"
                            + "\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    ByteArrayOutputStream headers = new ByteArrayOutputStream();
                    int last = 0;
                    while (last != 0x0d0a0d0a && headers.size() < 16384) {
                        int next = input.readUnsignedByte();
                        headers.write(next);
                        last = last << 8 | next;
                    }
                    if (!headers.toString(StandardCharsets.US_ASCII).startsWith("HTTP/1.1 101")) {
                        throw new IOException("WebSocket upgrade rejected");
                    }
                }
            } catch (IOException failure) {
                socket.close();
                throw failure;
            }
        }

        /**
         * Sends a minimal valid default-verifier login and checks the login-success opcode.
         *
         * @throws IOException if authentication is rejected or the socket closes
         */
        private void login() throws IOException {
            send(new byte[]{Events.LOG_IN, 0, 0, 0, 1, 'x'});
            if (read()[0] != Events.LOG_IN_SUCCESS) {
                throw new IOException("Login rejected");
            }
        }

        /**
         * Sends one bounded application frame with the selected transport envelope.
         *
         * @param bytes immutable application frame bytes
         * @throws IOException if transport submission fails
         */
        private void send(byte[] bytes) throws IOException {
            if (bytes.length > 65535) {
                throw new IOException("Client application frame too large");
            }
            if (!websocket) {
                output.writeShort(bytes.length);
                output.write(bytes);
            } else {
                output.writeByte(0x82);
                if (bytes.length < 126) {
                    output.writeByte(0x80 | bytes.length);
                }
                else {
                    output.writeByte(0x80 | 126);
                    output.writeShort(bytes.length);
                }
                int mask = ThreadLocalRandom.current().nextInt();
                output.writeInt(mask);
                byte[] masked = bytes.clone();
                for (int index = 0; index < masked.length; index++) {
                    masked[index] = (byte) (masked[index] ^ (mask >>> (24 - 8 * (index % 4)) & 255));
                }
                output.write(masked);
            }
            output.flush();
        }

        /**
         * Reads one complete binary server application frame with bounded allocation.
         *
         * @return the application payload
         * @throws IOException if the socket closes or a malformed envelope is received
         */
        private byte[] read() throws IOException {
            int length;
            if (!websocket) {
                length = input.readUnsignedShort();
            }
            else {
                int opcode = input.readUnsignedByte() & 15;
                int size = input.readUnsignedByte();
                if ((size & 0x80) != 0) {
                    throw new IOException("Unexpected masked server frame");
                }
                length = size & 127;
                if (length == 126) {
                    length = input.readUnsignedShort();
                }
                else if (length == 127) {
                    long large = input.readLong();
                    if (large > 65535 || large < 0) {
                        throw new IOException("Oversized server frame");
                    }
                    length = (int) large;
                }
                if (opcode == 8) {
                    throw new EOFException("WebSocket closed");
                }
                if (opcode != 2) {
                    throw new IOException("Unexpected WebSocket opcode " + opcode);
                }
            }
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length) {
                throw new EOFException("Truncated server frame");
            }
            return bytes;
        }

        /**
         * Requires terminal socket closure within the configured read deadline.
         *
         * @throws IOException if the server sends another application frame or never closes
         */
        private void requireClosed() throws IOException {
            try {
                byte[] control = read();
                if (control.length != 1 || control[0] != Events.DISCONNECT) {
                    throw new IOException("Expected terminal transport closure");
                }
                read();
                throw new IOException("Socket remained open after disconnect");
            } catch (EOFException | java.net.SocketException closed) {
                // Expected resource cleanup.
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
