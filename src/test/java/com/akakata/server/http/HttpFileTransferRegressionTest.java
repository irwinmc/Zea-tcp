package com.akakata.server.http;

import com.akakata.metrics.FlowControlMetrics;
import com.akakata.server.HttpFileTransferLimits;
import io.netty.channel.*;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.*;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises real static-file routing with delayed outbound writes and virtual deadlines.
 */
class HttpFileTransferRegressionTest {

    /**
     * Trusted fixture root containing only synthetic files.
     */
    @TempDir
    Path directory;

    /**
     * Creates the file-transfer regression fixture.
     */
    HttpFileTransferRegressionTest() {
    }

    /**
     * Bounds pipelined files before another region or response header is submitted.
     *
     * @throws Exception if the fixture cannot be opened
     */
    @Test
    void connectionQuotaClosesWithoutInterleavingAnErrorIntoAnEarlierResponse() throws Exception {
        HttpFileTransfers owner = owner(2, 8);
        HeldTransport transport = new HeldTransport();
        EmbeddedChannel channel = channel(owner, transport);
        long rejected = FlowControlMetrics.HTTP_FILE_TRANSFER_REJECTIONS.sum();
        try {
            request(channel);
            request(channel);
            assertEquals(2, owner.pendingTransfers());
            assertEquals(2, transport.regions.size());
            FullHttpRequest third = request(channel);
            assertEquals(0, third.refCnt());
            assertFalse(channel.isOpen());
            assertEquals(2, transport.regions.size());
            assertEquals(0, owner.pendingTransfers());
            assertEquals(rejected + 1, FlowControlMetrics.HTTP_FILE_TRANSFER_REJECTIONS.sum());
            for (DefaultFileRegion region : transport.regions) {
                assertEquals(0, region.refCnt());
                assertFalse(region.isOpen());
            }
            int responses = 0;
            Object message;
            while ((message = channel.readOutbound()) != null) {
                if (message instanceof HttpResponse response) {
                    assertEquals(200, response.status().code());
                    responses++;
                }
                ReferenceCountUtil.release(message);
            }
            assertEquals(2, responses);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Shares file admission across channels and permits reuse only after cleanup.
     *
     * @throws Exception if the fixture cannot be opened
     */
    @Test
    void aggregateQuotaRejectsAnotherConnectionWithoutClosingTheAdmittedOne() throws Exception {
        HttpFileTransfers owner = owner(2, 1);
        HeldTransport firstTransport = new HeldTransport();
        HeldTransport secondTransport = new HeldTransport();
        EmbeddedChannel first = channel(owner, firstTransport);
        EmbeddedChannel second = channel(owner, secondTransport);
        EmbeddedChannel replacement = channel(owner, new HeldTransport());
        try {
            request(first);
            request(second);
            assertTrue(first.isActive());
            assertFalse(second.isOpen());
            assertEquals(0, secondTransport.regions.size());
            assertEquals(1, owner.pendingTransfers());
            first.close();
            assertEquals(0, owner.pendingTransfers());
            request(replacement);
            assertEquals(1, owner.pendingTransfers());
            assertTrue(replacement.isActive());
        } finally {
            first.finishAndReleaseAll();
            second.finishAndReleaseAll();
            replacement.finishAndReleaseAll();
        }
        assertEquals(0, owner.pendingTransfers());
    }

    /**
     * Keeps an old transfer's deadline independent of subsequent incoming requests.
     *
     * @throws Exception if fixture routing fails
     */
    @Test
    void newRequestsCannotExtendTheDeadlineOfAStalledFile() throws Exception {
        HttpFileTransfers owner = owner(2, 8);
        HeldTransport transport = new HeldTransport();
        EmbeddedChannel channel = channel(owner, transport);
        long timedOut = FlowControlMetrics.HTTP_FILE_TRANSFER_TIMEOUTS.sum();
        try {
            request(channel);
            channel.advanceTimeBy(30, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            assertTrue(channel.isActive());
            request(channel);
            channel.advanceTimeBy(31, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            channel.runPendingTasks();
            assertFalse(channel.isOpen());
            assertEquals(0, owner.pendingTransfers());
            assertEquals(timedOut + 1, FlowControlMetrics.HTTP_FILE_TRANSFER_TIMEOUTS.sum());
            for (DefaultFileRegion region : transport.regions) {
                assertEquals(0, region.refCnt());
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Reserves a response until both its file write and final content write complete.
     *
     * @throws Exception if fixture routing fails
     */
    @Test
    void successfulWritesReleaseOnceAndCancelTheTransferTimer() throws Exception {
        HttpFileTransfers owner = owner(1, 1);
        HeldTransport transport = new HeldTransport();
        EmbeddedChannel channel = channel(owner, transport);
        long timedOut = FlowControlMetrics.HTTP_FILE_TRANSFER_TIMEOUTS.sum();
        try {
            request(channel);
            transport.complete(0);
            assertEquals(1, owner.pendingTransfers());
            transport.complete(1);
            assertEquals(0, owner.pendingTransfers());
            channel.advanceTimeBy(61, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            assertTrue(channel.isActive());
            assertEquals(timedOut, FlowControlMetrics.HTTP_FILE_TRANSFER_TIMEOUTS.sum());
            request(channel);
            assertEquals(1, owner.pendingTransfers());
        } finally {
            channel.finishAndReleaseAll();
        }
        assertEquals(0, owner.pendingTransfers());
    }

    /**
     * Discards all owned regions after a failed response terminator write.
     *
     * @throws Exception if fixture routing fails
     */
    @Test
    void rejectedFinalWriteClosesTheFileAndReturnsItsReservation() throws Exception {
        HttpFileTransfers owner = owner(1, 1);
        HeldTransport transport = new HeldTransport();
        EmbeddedChannel channel = channel(owner, transport);
        try {
            request(channel);
            transport.fail(1);
            assertFalse(channel.isOpen());
            assertEquals(0, owner.pendingTransfers());
            assertEquals(0, transport.regions.getFirst().refCnt());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Cancelling an accepted file write aborts the connection and its native ownership.
     *
     * @throws Exception if fixture routing fails
     */
    @Test
    void cancelledWriteDoesNotLeaveAHandleOrAnAdmissionSlot() throws Exception {
        HttpFileTransfers owner = owner(1, 1);
        HeldTransport transport = new HeldTransport();
        EmbeddedChannel channel = channel(owner, transport);
        try {
            request(channel);
            assertTrue(transport.pending.getFirst().promise().cancel(false));
            assertFalse(channel.isOpen());
            assertEquals(0, owner.pendingTransfers());
            assertEquals(0, transport.regions.getFirst().refCnt());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Releases reservations on missing files and bodyless conditional responses.
     *
     * @throws Exception if fixture creation or routing fails
     */
    @Test
    void missingAndUnmodifiedFilesReturnTheirAdmissionWithoutSchedulingAStaleTimeout() throws Exception {
        HttpFileTransfers owner = owner(1, 1);
        EmbeddedChannel missing = channel(owner, new HeldTransport());
        EmbeddedChannel unmodified = channel(owner, new HeldTransport());
        try {
            missing.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/missing.txt"));
            assertEquals(0, owner.pendingTransfers());
            FullHttpRequest conditional = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/fixture.txt");
            conditional.headers().set(HttpHeaderNames.IF_MODIFIED_SINCE,
                    DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now().plusDays(1)));
            unmodified.writeInbound(conditional);
            assertEquals(0, owner.pendingTransfers());
            FullHttpResponse response = unmodified.readOutbound();
            try {
                assertEquals(304, response.status().code());
            } finally {
                response.release();
            }
            assertEquals(0, conditional.refCnt());
        } finally {
            missing.finishAndReleaseAll();
            unmodified.finishAndReleaseAll();
        }
    }

    /**
     * Confirms deadline cleanup closes the actual file handle even if a write never completes.
     *
     * @throws Exception if the real file handle cannot be opened
     */
    @Test
    void timeoutClosesAnAttachedHandleBeforeReusingTheQuota() throws Exception {
        HttpFileTransfers owner = owner(1, 1);
        EmbeddedChannel channel = new EmbeddedChannel();
        Path path = Files.writeString(directory.resolve("handle.txt"), "synthetic data");
        FileChannel file = FileChannel.open(path, StandardOpenOption.READ);
        try {
            HttpFileTransfers.Lease lease = owner.tryAcquire(channel);
            assertNotNull(lease);
            lease.attach(file);
            assertTrue(file.isOpen());
            channel.advanceTimeBy(61, TimeUnit.SECONDS);
            channel.runScheduledPendingTasks();
            assertFalse(file.isOpen());
            assertEquals(0, owner.pendingTransfers());
            lease.close();
            assertEquals(0, owner.pendingTransfers());
        } finally {
            file.close();
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Enforces a global reservation maximum during simultaneous admission on different connections.
     *
     * @throws Exception if concurrent tasks do not complete
     */
    @Test
    void concurrentConnectionsCannotOverdrawAggregateAdmission() throws Exception {
        HttpFileTransfers owner = owner(1, 4);
        AtomicInteger acquired = new AtomicInteger();
        List<CompletableFuture<Void>> attempts = new ArrayList<>();
        List<HttpFileTransfers.Lease> leases = java.util.Collections.synchronizedList(new ArrayList<>());
        List<EmbeddedChannel> channels = new ArrayList<>();
        try {
            for (int index = 0; index < 16; index++) {
                EmbeddedChannel channel = new EmbeddedChannel();
                channels.add(channel);
                attempts.add(CompletableFuture.runAsync(() -> {
                    HttpFileTransfers.Lease lease = owner.tryAcquire(channel);
                    if (lease != null) {
                        acquired.incrementAndGet();
                        leases.add(lease);
                    }
                }));
            }
            CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new)).get(2, TimeUnit.SECONDS);
            assertEquals(4, acquired.get());
            assertEquals(4, owner.pendingTransfers());
        } finally {
            for (HttpFileTransfers.Lease lease : leases) {
                lease.close();
            }
            for (EmbeddedChannel channel : channels) {
                channel.finishAndReleaseAll();
            }
        }
        assertEquals(0, owner.pendingTransfers());
    }

    /**
     * Creates a generous virtual-time deadline independent of wall-clock test scheduling.
     *
     * @param perConnection maximum pending responses per connection
     * @param total aggregate transfer allowance
     * @return a bounded file-transfer owner
     */
    private static HttpFileTransfers owner(int perConnection, int total) {
        return new HttpFileTransfers(new HttpFileTransferLimits(perConnection, total, 60_000));
    }

    /**
     * Routes genuine static requests through the shared admission owner.
     *
     * @param owner shared file-transfer owner
     * @param transport controllable outbound transport
     * @return active embedded HTTP connection
     * @throws IOException if fixture creation fails
     */
    private EmbeddedChannel channel(HttpFileTransfers owner, HeldTransport transport) throws IOException {
        Files.writeString(directory.resolve("fixture.txt"), "synthetic static content");
        StaticFileHandler handler = new StaticFileHandler(new StaticResourceResolver(directory), owner);
        return new EmbeddedChannel(transport, new SimpleChannelInboundHandler<FullHttpRequest>() {
            @Override
            protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) throws Exception {
                handler.handleStaticFile(context, request);
            }
        });
    }

    /**
     * Sends a keep-alive request whose borrowed source is released by the route.
     *
     * @param channel target connection
     * @return consumed request for reference-count assertions
     */
    private static FullHttpRequest request(EmbeddedChannel channel) {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/fixture.txt");
        channel.writeInbound(request);
        return request;
    }

    /**
     * Holds accepted native writes and discards them through the normal channel-close path.
     */
    private static final class HeldTransport extends ChannelOutboundHandlerAdapter {

        /**
         * Captured region identities retained for cleanup assertions.
         */
        private final List<DefaultFileRegion> regions = new ArrayList<>();

        /**
         * Accepted outbound writes awaiting completion or channel-close discard.
         */
        private final List<PendingWrite> pending = new ArrayList<>();

        /**
         * Captures a native message with its write completion.
         *
         * @param message owned outbound message
         * @param promise accepted write completion
         */
        private record PendingWrite(Object message, ChannelPromise promise) {
        }

        /**
         * Creates a delayed native transport.
         */
        private HeldTransport() {
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            if (message instanceof DefaultFileRegion region) {
                regions.add(region);
                pending.add(new PendingWrite(message, promise));
            } else if (message instanceof LastHttpContent && !(message instanceof FullHttpResponse)) {
                pending.add(new PendingWrite(message, promise));
            } else {
                context.write(message, promise);
            }
        }

        /**
         * Completes an accepted write after releasing the transport's reference.
         *
         * @param index index in the pending write sequence
         */
        private void complete(int index) {
            PendingWrite write = pending.set(index, null);
            ReferenceCountUtil.release(write.message());
            write.promise().trySuccess();
        }

        /**
         * Fails an accepted write after releasing the transport's reference.
         *
         * @param index index in the pending write sequence
         */
        private void fail(int index) {
            PendingWrite write = pending.set(index, null);
            ReferenceCountUtil.release(write.message());
            write.promise().tryFailure(new IOException("Injected response write failure"));
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void close(ChannelHandlerContext context, ChannelPromise promise) {
            List<PendingWrite> discarded = new ArrayList<>(pending);
            pending.clear();
            context.close(promise);
            for (PendingWrite write : discarded) {
                if (write != null) {
                    ReferenceCountUtil.release(write.message());
                    write.promise().tryFailure(new IOException("Closed fixture transport"));
                }
            }
        }
    }
}
