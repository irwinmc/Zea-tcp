package com.akakata.server.http;

import com.akakata.server.HttpFileTransferLimits;
import com.akakata.handlers.HttpRequestHandler;
import com.akakata.metrics.FlowControlMetrics;
import com.akakata.context.NetworkBootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static com.akakata.server.http.HttpFileTestSupport.await;

/**
 * Verifies filesystem work executes outside the HTTP I/O callback.
 */
class StaticFileOffloadRegressionTest {

    /**
     * Temporary trusted file root.
     */
    @TempDir
    Path directory;

    /**
     * Creates the file-offload fixture.
     */
    StaticFileOffloadRegressionTest() {
    }

    /**
     * Captures the thread executing the real contained-file resolver.
     *
     * @throws Exception if the resource or synchronization fails
     */
    @Test
    void pathResolutionAndOpenRunOutsideTheIoCallback() throws Exception {
        Files.write(directory.resolve("fixture.txt"), new byte[]{1, 2, 3});
        StaticResourceResolver resolver = new StaticResourceResolver(directory);
        AtomicReference<Thread> worker = new AtomicReference<>();
        CountDownLatch opened = new CountDownLatch(1);
        HttpFileTransfers transfers = new HttpFileTransfers(HttpFileTransferLimits.DEFAULT);
        StaticFileHandler handler = new StaticFileHandler(uri -> {
            worker.set(Thread.currentThread());
            try {
                return resolver.open(uri);
            } finally {
                opened.countDown();
            }
        }, transfers);
        EmbeddedChannel channel = new EmbeddedChannel(new SimpleChannelInboundHandler<FullHttpRequest>() {
            @Override
            protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) throws Exception {
                handler.handleStaticFile(context, request);
            }
        });
        try {
            channel.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/fixture.txt"));
            assertTrue(opened.await(2, TimeUnit.SECONDS));
            assertNotSame(Thread.currentThread(), worker.get());
        } finally {
            channel.runPendingTasks();
            channel.finishAndReleaseAll();
            transfers.close();
        }
    }

    /**
     * Preserves file/file/dynamic response order while another connection stays responsive.
     *
     * @throws Exception if the fixture or synchronization fails
     */
    @Test
    void slowOpenPreservesPipelinedRoutingOrderAndDoesNotBlockAnotherConnection() throws Exception {
        Files.write(directory.resolve("first.txt"), new byte[]{1});
        Files.write(directory.resolve("second.txt"), new byte[]{2, 3});
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        HttpFileTransfers owner = new HttpFileTransfers(new HttpFileTransferLimits(3, 3, 60_000));
        StaticResourceResolver resolver = new StaticResourceResolver(directory);
        StaticFileHandler handler = new StaticFileHandler(uri -> {
            if (uri.equals("/first.txt")) {
                entered.countDown();
                gate(resume);
            }
            return resolver.open(uri);
        }, owner);
        EmbeddedChannel channel = routed(handler);
        EmbeddedChannel other = routed(handler);
        FullHttpRequest second = request("/second.txt");
        FullHttpRequest dynamic = request("/");
        try {
            channel.writeInbound(request("/first.txt"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertFalse(channel.config().isAutoRead());
            channel.writeInbound(second);
            channel.writeInbound(dynamic);
            assertEquals(1, second.refCnt());
            assertEquals(1, dynamic.refCnt());
            assertTrue(channel.outboundMessages().isEmpty());
            other.writeInbound(request("/"));
            FullHttpResponse responsive = other.readOutbound();
            assertEquals(201, responsive.status().code());
            responsive.release();
            resume.countDown();
            await(channel, () -> channel.outboundMessages().size() == 7);
            List<Integer> lengths = new ArrayList<>();
            Object message;
            while ((message = channel.readOutbound()) != null) {
                if (message instanceof HttpResponse) {
                    HttpResponse response = (HttpResponse) message;
                    lengths.add(response.status().code() == 201 ? -1
                            : Integer.parseInt(response.headers().get("Content-Length")));
                }
                ReferenceCountUtil.release(message);
            }
            assertEquals(java.util.Arrays.asList(1, 2, -1), lengths);
            assertEquals(0, second.refCnt());
            assertEquals(0, dynamic.refCnt());
            assertTrue(channel.config().isAutoRead());
        } finally {
            resume.countDown();
            channel.finishAndReleaseAll();
            other.finishAndReleaseAll();
            owner.close();
        }
    }

    /**
     * Keeps admission and metrics until a disconnected in-flight open closes its late result.
     *
     * @throws Exception if the fixture or synchronization fails
     */
    @Test
    void disconnectDuringOpenKeepsCreditsUntilLateHandleCleanup() throws Exception {
        lateOpen(false);
    }

    /**
     * Covers a stalled open with the original absolute transfer deadline.
     *
     * @throws Exception if the fixture or synchronization fails
     */
    @Test
    void timeoutDuringOpenKeepsCreditsUntilLateHandleCleanup() throws Exception {
        lateOpen(true);
    }

    /**
     * Shares the disconnected and timed-out late-result ownership assertions.
     *
     * @param timeout whether to trigger the virtual transfer deadline
     * @throws Exception if synchronization or file opening fails
     */
    private void lateOpen(boolean timeout) throws Exception {
        Files.write(directory.resolve("fixture.txt"), new byte[]{1});
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<FileChannel> file = new AtomicReference<>();
        long baseline = FlowControlMetrics.PENDING_HTTP_FILE_TRANSFERS.sum();
        long timeouts = FlowControlMetrics.HTTP_FILE_TRANSFER_TIMEOUTS.sum();
        HttpFileTransfers owner = new HttpFileTransfers(new HttpFileTransferLimits(1, 1, 100));
        StaticResourceResolver resolver = new StaticResourceResolver(directory);
        StaticFileHandler handler = new StaticFileHandler(uri -> {
            entered.countDown();
            gate(resume);
            StaticResourceResolver.Resource resource = resolver.open(uri);
            file.set(resource.channel());
            return resource;
        }, owner);
        EmbeddedChannel channel = routed(handler);
        EmbeddedChannel next = new EmbeddedChannel();
        FullHttpRequest request = request("/fixture.txt");
        try {
            channel.writeInbound(request);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(0, request.refCnt());
            if (timeout) {
                channel.advanceTimeBy(101, TimeUnit.MILLISECONDS);
                channel.runScheduledPendingTasks();
            } else {
                channel.close();
            }
            assertFalse(channel.isOpen());
            assertEquals(1, owner.pendingTransfers());
            assertEquals(baseline + 1, FlowControlMetrics.PENDING_HTTP_FILE_TRANSFERS.sum());
            assertNull(owner.tryAcquire(next));
            resume.countDown();
            await(channel, () -> owner.pendingTransfers() == 0);
            assertNotNull(file.get());
            assertFalse(file.get().isOpen());
            assertTrue(channel.outboundMessages().isEmpty());
            assertEquals(baseline, FlowControlMetrics.PENDING_HTTP_FILE_TRANSFERS.sum());
            assertEquals(timeouts + (timeout ? 1 : 0), FlowControlMetrics.HTTP_FILE_TRANSFER_TIMEOUTS.sum());
            HttpFileTransfers.Lease replacement = owner.tryAcquire(next);
            assertNotNull(replacement);
            replacement.close();
        } finally {
            resume.countDown();
            channel.finishAndReleaseAll();
            next.finishAndReleaseAll();
            owner.close();
        }
    }

    /**
     * Discards retained routes on current-batch overflow without running them inline.
     *
     * @throws Exception if the fixture or synchronization fails
     */
    @Test
    void fifoOverflowClosesAndReleasesQueuedNativeRequests() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicInteger opens = new AtomicInteger();
        HttpFileTransfers owner = new HttpFileTransfers(new HttpFileTransferLimits(2, 2, 60_000));
        StaticFileHandler handler = new StaticFileHandler(uri -> {
            opens.incrementAndGet();
            entered.countDown();
            gate(resume);
            throw new IOException("Injected filesystem failure");
        }, owner);
        EmbeddedChannel channel = routed(handler);
        FullHttpRequest second = request("/");
        FullHttpRequest third = request("/");
        try {
            channel.writeInbound(request("/slow.txt"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            channel.writeInbound(second);
            channel.writeInbound(third);
            assertFalse(channel.isOpen());
            assertEquals(0, second.refCnt());
            assertEquals(0, third.refCnt());
            assertEquals(1, opens.get());
            assertEquals(1, owner.pendingTransfers());
            assertTrue(channel.outboundMessages().isEmpty());
        } finally {
            resume.countDown();
            channel.finishAndReleaseAll();
            owner.close();
        }
    }

    /**
     * Cancels queued opens during shutdown while retaining actual running ownership.
     *
     * @throws Exception if synchronization fails
     */
    @Test
    void shutdownWaitsForRunningOpensAndDiscardsQueuedOperations() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean queuedRan = new AtomicBoolean();
        HttpFileTransfers owner = new HttpFileTransfers(new HttpFileTransferLimits(1, 3, 60_000));
        List<EmbeddedChannel> channels = new ArrayList<>();
        try {
            for (int index = 0; index < 3; index++) {
                final boolean queued = index == 2;
                StaticFileHandler handler = new StaticFileHandler(uri -> {
                    if (queued) {
                        queuedRan.set(true);
                    }
                    entered.countDown();
                    gate(resume);
                    throw new IOException("Injected filesystem failure");
                }, owner);
                EmbeddedChannel channel = routed(handler);
                channels.add(channel);
                channel.writeInbound(request("/slow.txt"));
                if (index == 1) {
                    assertTrue(entered.await(2, TimeUnit.SECONDS));
                }
            }
            assertEquals(3, owner.pendingTransfers());
            CompletableFuture<Void> completion = owner.closeAsync();
            assertFalse(completion.isDone());
            assertEquals(3, owner.pendingTransfers());
            assertFalse(queuedRan.get());
            for (EmbeddedChannel channel : channels) {
                assertFalse(channel.isOpen());
            }
            resume.countDown();
            completion.get(2, TimeUnit.SECONDS);
            assertFalse(queuedRan.get());
            assertEquals(0, owner.pendingTransfers());
            assertNull(owner.tryAcquire(channels.get(0)));
        } finally {
            resume.countDown();
            for (EmbeddedChannel channel : channels) {
                channel.finishAndReleaseAll();
            }
            owner.close();
        }
    }

    /**
     * Leaves an application-disabled auto-read flag disabled after opening completes.
     *
     * @throws Exception if the fixture file cannot be created
     */
    @Test
    void applicationDisabledAutoReadIsPreserved() throws Exception {
        Files.write(directory.resolve("fixture.txt"), new byte[]{1});
        try (HttpFileTransfers owner = new HttpFileTransfers(HttpFileTransferLimits.DEFAULT)) {
            EmbeddedChannel channel = routed(new StaticFileHandler(new StaticResourceResolver(directory), owner));
            try {
                channel.config().setAutoRead(false);
                channel.writeInbound(request("/fixture.txt"));
                await(channel, () -> !channel.outboundMessages().isEmpty());
                assertFalse(channel.config().isAutoRead());
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    /**
     * Holds capacity through actual descriptor closure, including a close exception.
     *
     * @param failClose whether closure should report an injected I/O failure
     * @throws Exception if the fixture or synchronization fails
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fileCloseRunsOffLoopAndReturnsCreditsOnlyAfterItExits(boolean failClose) throws Exception {
        Files.write(directory.resolve("fixture.txt"), new byte[]{1});
        StaticResourceResolver resolver = new StaticResourceResolver(directory);
        CountDownLatch closing = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicReference<Thread> closeThread = new AtomicReference<>();
        AtomicReference<FileChannel> actual = new AtomicReference<>();
        AtomicReference<DefaultFileRegion> region = new AtomicReference<>();
        HttpFileTransfers owner = new HttpFileTransfers(new HttpFileTransferLimits(1, 1, 60_000));
        StaticFileHandler handler = new StaticFileHandler(uri -> {
            StaticResourceResolver.Resource opened = resolver.open(uri);
            actual.set(opened.channel());
            ObservedFileChannel file = new ObservedFileChannel(opened.channel(), () -> {
                closeThread.set(Thread.currentThread());
                closing.countDown();
                try {
                    gate(resume);
                } catch (IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            }, failClose);
            return new StaticResourceResolver.Resource(file, opened.length(), opened.modified(), opened.name());
        }, owner);
        EmbeddedChannel channel = routed(handler);
        channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                if (message instanceof DefaultFileRegion) {
                    region.set((DefaultFileRegion) message);
                    ReferenceCountUtil.release(message);
                    promise.setSuccess();
                } else {
                    context.write(message, promise);
                }
            }
        });
        try {
            channel.writeInbound(request("/fixture.txt"));
            await(channel, () -> closing.getCount() == 0);
            assertNotSame(Thread.currentThread(), closeThread.get());
            assertTrue(actual.get().isOpen());
            assertEquals(0, region.get().refCnt());
            assertEquals(1, owner.pendingTransfers());
            CompletableFuture<Void> completion = owner.closeAsync();
            assertFalse(completion.isDone());
            assertEquals(1, owner.pendingTransfers());
            resume.countDown();
            completion.get(2, TimeUnit.SECONDS);
            assertFalse(actual.get().isOpen());
            assertEquals(0, owner.pendingTransfers());
        } finally {
            resume.countDown();
            channel.finishAndReleaseAll();
            owner.close();
        }
    }

    /**
     * Checks actual HTTP encoding and file-region body bytes on a loopback NIO socket.
     *
     * @throws Exception if listener setup, transfer or cleanup fails
     */
    @Test
    void loopbackTransportPreservesPipelinedBodyBytes() throws Exception {
        byte[] first = new byte[128 * 1024];
        for (int index = 0; index < first.length; index++) {
            first[index] = (byte) index;
        }
        byte[] second = "second response".getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve("first.bin"), first);
        Files.write(directory.resolve("second.bin"), second);
        try (HttpFileTransfers owner = new HttpFileTransfers(new HttpFileTransferLimits(2, 2, 60_000));
             NetworkBootstrap network = new NetworkBootstrap(1, 1)) {
            StaticFileHandler handler = new StaticFileHandler(new StaticResourceResolver(directory), owner);
            Channel listener = new ServerBootstrap().group(network.getBossGroup(), network.getWorkerGroup())
                    .channel(NioServerSocketChannel.class).childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline().addLast(new io.netty.handler.codec.http.HttpServerCodec());
                            channel.pipeline().addLast(new io.netty.handler.codec.http.HttpObjectAggregator(65536));
                            channel.pipeline().addLast(new HttpRequestHandler(handler, null, null, null, null, null));
                        }
                    }).bind("127.0.0.1", 0).sync().channel();
            try (Socket socket = new Socket()) {
                socket.connect((InetSocketAddress) listener.localAddress(), 2000);
                socket.setSoTimeout(5000);
                String requests = "GET /first.bin HTTP/1.1\r\nHost: localhost\r\n\r\n"
                        + "GET /second.bin HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(requests.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                assertArrayEquals(first, readBody(socket.getInputStream()));
                assertArrayEquals(second, readBody(socket.getInputStream()));
                assertEquals(-1, socket.getInputStream().read());
                await(null, () -> owner.pendingTransfers() == 0);
            } finally {
                listener.close().sync();
            }
        }
    }

    /**
     * Reads one length-delimited response without consuming any subsequent response.
     *
     * @param input connected HTTP socket input
     * @return exactly one response body
     * @throws IOException if framing is incomplete or the socket fails
     */
    private static byte[] readBody(InputStream input) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        String text;
        do {
            int value = input.read();
            if (value < 0 || header.size() > 8192) {
                throw new IOException("Incomplete HTTP response header");
            }
            header.write(value);
            text = new String(header.toByteArray(), StandardCharsets.US_ASCII);
        } while (!text.endsWith("\r\n\r\n"));
        assertTrue(text.startsWith("HTTP/1.1 200"));
        int length = -1;
        for (String line : text.split("\r\n")) {
            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                length = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        assertTrue(length >= 0);
        byte[] body = new byte[length];
        int position = 0;
        while (position < length) {
            int count = input.read(body, position, length - position);
            if (count < 0) {
                throw new IOException("Truncated HTTP response body");
            }
            position += count;
        }
        return body;
    }

    /**
     * Builds a real gateway with a distinct synchronous dynamic response.
     *
     * @param handler asynchronous static presenter
     * @return an active embedded connection
     */
    private static EmbeddedChannel routed(StaticFileHandler handler) {
        IndexPageHandler index = new IndexPageHandler() {
            @Override
            public void createIndexPage(ChannelHandlerContext context, FullHttpRequest request) {
                context.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
                        HttpResponseStatus.CREATED, Unpooled.EMPTY_BUFFER));
            }
        };
        return new EmbeddedChannel(new HttpRequestHandler(handler, index, null, null, null, null));
    }

    /**
     * Creates a request with caller ownership transferred to the inbound pipeline.
     *
     * @param uri requested route
     * @return a new full request
     */
    private static FullHttpRequest request(String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
    }

    /**
     * Blocks only the filesystem worker under explicit test control.
     *
     * @param resume worker continuation gate
     * @throws IOException if interrupted or the test fails to release its worker
     */
    private static void gate(CountDownLatch resume) throws IOException {
        try {
            if (!resume.await(5, TimeUnit.SECONDS)) {
                throw new IOException("Test did not release filesystem worker");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException(failure);
        }
    }
}
