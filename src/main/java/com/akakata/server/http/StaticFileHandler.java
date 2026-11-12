package com.akakata.server.http;

import com.akakata.server.HttpFileTransferLimits;
import com.akakata.metrics.FlowControlMetrics;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.*;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLConnection;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Objects;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Serves contained regular files using an owned handle and bounded transport writes.
 *
 * <p>The resolver controls path policy independently of HTTP response generation.
 * Every acquired handle is closed after transfer or on validation, cache and write
 * failures. Static directories and their trusted parents must be application-controlled.
 * Transfer admission happens before opening a file. Pending responses have independent
 * absolute deadlines; quota rejection closes the connection without interleaving an
 * error response into a pending body.
 */
public class StaticFileHandler extends AbstractHttpHandler {

    /**
     * HTTP date format retained for application integrations.
     */
    public static final String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz";

    /**
     * HTTP date timezone.
     */
    public static final String HTTP_DATE_GMT_TIMEZONE = "GMT";

    /**
     * Default cache lifetime in seconds.
     */
    public static final int HTTP_CACHE_SECONDS = 60;

    /**
     * Default static directory beneath the application working directory.
     */
    public static final String HTML_FILE_DIR = "html";

    /**
     * Logger for resource-opening and cleanup failures.
     */
    private static final Logger LOG = LoggerFactory.getLogger(StaticFileHandler.class);

    /**
     * Thread-safe HTTP date formatter.
     */
    private static final DateTimeFormatter DATES = DateTimeFormatter.ofPattern(HTTP_DATE_FORMAT, Locale.US)
            .withZone(ZoneId.of(HTTP_DATE_GMT_TIMEZONE));

    /**
     * Borrowed path resolver; each result transfers one file-handle reference.
     */
    private final ResourceOpener resolver;

    /**
     * Shared file-handle admission and response-completion policy.
     */
    private final HttpFileTransfers transfers;

    /**
     * Per-connection routing FIFO shared with the HTTP gateway.
     */
    private static final AttributeKey<ConnectionRequests> REQUESTS =
            AttributeKey.valueOf(StaticFileHandler.class, "orderedRequests");

    /**
     * Serves the application's working-directory html subtree.
     */
    public StaticFileHandler() {
        this(new StaticResourceResolver(Path.of(System.getProperty("user.dir")).resolve(HTML_FILE_DIR)));
    }

    /**
     * Creates an HTTP presenter for an independently configured static resolver.
     *
     * @param resolver trusted-root resource resolver
     */
    public StaticFileHandler(StaticResourceResolver resolver) {
        this(resolver, new HttpFileTransfers(HttpFileTransferLimits.DEFAULT));
    }

    /**
     * Uses the standard static directory with a context-wide transfer owner.
     *
     * @param transfers shared file admission and deadline owner
     */
    public StaticFileHandler(HttpFileTransfers transfers) {
        this(new StaticResourceResolver(Path.of(System.getProperty("user.dir")).resolve(HTML_FILE_DIR)), transfers);
    }

    /**
     * Selects static resources and an independently configured transfer policy.
     *
     * @param resolver trusted directory resolver
     * @param transfers file-handle owner shared across all routed connections
     */
    public StaticFileHandler(StaticResourceResolver resolver, HttpFileTransfers transfers) {
        this(resolver::open, transfers);
    }

    /**
     * Selects the filesystem operation without changing response policy.
     *
     * @param resolver trusted file-opening operation
     * @param transfers shared file admission owner
     */
    StaticFileHandler(ResourceOpener resolver, HttpFileTransfers transfers) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.transfers = Objects.requireNonNull(transfers, "transfers");
    }

    /**
     * Opens one contained resource with owned file-channel output.
     */
    @FunctionalInterface
    interface ResourceOpener {

        /**
         * Resolves the URI without borrowing native request data.
         *
         * @param uri detached request URI
         * @return the owned opened resource
         * @throws IOException if resolution or opening fails
         */
        StaticResourceResolver.Resource open(String uri) throws IOException;
    }

    /**
     * Defers a later routed request while an earlier static file is opening.
     *
     * <p>The native request is retained only when queued and is released after routing
     * or discard. The existing per-connection transfer limit bounds this FIFO; the
     * HTTP aggregator bounds each body. Overflow closes without inserting a response.
     *
     * @param ctx connection's I/O context
     * @param request borrowed full request
     * @param route synchronous routing action borrowing the retained request
     * @return whether the request was deferred or discarded
     */
    public boolean deferRequest(ChannelHandlerContext ctx, FullHttpRequest request, Runnable route) {
        ConnectionRequests requests = ctx.channel().attr(REQUESTS).get();
        if (requests == null || (!requests.opening && !requests.closed)) {
            return false;
        }
        request.retain();
        requests.submit(() -> {
            try {
                route.run();
            } finally {
                request.release();
            }
        }, request::release);
        return true;
    }

    /**
     * Admits detached request metadata and opens files outside the I/O loop.
     *
     * <p>Results return to the original loop in request order. Deadlines include
     * opening; disconnected or timed-out work keeps its credits until cleanup exits.
     *
     * @param ctx connection's event-loop context
     * @param request borrowed HTTP request, not retained by filesystem work
     * @throws Exception if synchronous HTTP presentation fails
     */
    public void handleStaticFile(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        ConnectionRequests requests = ctx.channel().attr(REQUESTS).get();
        if (requests == null) {
            requests = new ConnectionRequests(ctx);
            ctx.channel().attr(REQUESTS).set(requests);
        }
        HttpResponseStatus invalid = null;
        Instant modifiedSince = null;
        if (!request.decoderResult().isSuccess()) {
            invalid = BAD_REQUEST;
        } else if (request.method() != GET) {
            invalid = METHOD_NOT_ALLOWED;
        } else {
            String header = request.headers().get(IF_MODIFIED_SINCE);
            if (header != null && !header.isBlank()) {
                try {
                    modifiedSince = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(header));
                } catch (DateTimeParseException failure) {
                    invalid = BAD_REQUEST;
                }
            }
        }
        if (invalid != null) {
            HttpResponseStatus status = invalid;
            requests.submit(() -> sendError(ctx, status), () -> {
                // No file or native request is owned by this validation response.
            });
            return;
        }
        HttpFileTransfers.Lease lease = transfers.tryAcquire(ctx.channel());
        if (lease == null) {
            ctx.close();
            return;
        }
        String uri = request.uri();
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        Instant conditional = modifiedSince;
        ConnectionRequests ordered = requests;
        requests.submit(() -> {
            if (lease.isClosing()) {
                return;
            }
            ordered.opening = true;
            ordered.pauseReads();
            try {
                lease.open(() -> resolver.open(uri), (resource, failure) -> {
                    try {
                        present(ctx, keepAlive, conditional, resource, failure, lease);
                    } catch (Throwable presentationFailure) {
                        ctx.fireExceptionCaught(presentationFailure);
                        ctx.close();
                    } finally {
                        ordered.opening = false;
                        ordered.drain();
                    }
                });
            } catch (RuntimeException failure) {
                ordered.opening = false;
                lease.close();
                throw failure;
            }
        }, lease::close);
    }

    /**
     * Applies a completed open on the I/O loop and transfers response ownership.
     *
     * @param ctx original connection context
     * @param keepAlive detached connection policy
     * @param conditional detached cache timestamp, or null
     * @param resource opened resource, or null after failure
     * @param failure filesystem failure, or null after success
     * @param lease admitted file owner
     */
    private void present(ChannelHandlerContext ctx, boolean keepAlive, Instant conditional,
                                StaticResourceResolver.Resource resource, Throwable failure,
                                HttpFileTransfers.Lease lease) {
        boolean monitored = false;
        try {
            if (failure instanceof AccessDeniedException || failure instanceof FileSystemException
                    && !(failure instanceof NoSuchFileException)) {
                sendError(ctx, FORBIDDEN);
            } else if (failure instanceof NoSuchFileException) {
                sendError(ctx, NOT_FOUND);
            } else if (failure instanceof IOException) {
                LOG.warn("Unable to open static resource", failure);
                sendError(ctx, INTERNAL_SERVER_ERROR);
            } else if (failure != null) {
                ctx.fireExceptionCaught(failure);
                ctx.close();
            } else if (conditional != null && resource.modified().toInstant().getEpochSecond() <= conditional.getEpochSecond()) {
                sendNotModified(ctx);
            } else {
                writeResource(ctx, keepAlive, resource, lease);
                monitored = true;
            }
        } finally {
            if (!monitored) {
                lease.close();
            }
        }
    }

    /**
     * Submits one ordered HTTP response while transferring region ownership to Netty.
     *
     * @param ctx connection's event-loop context
     * @param keepAlive detached request keep-alive policy
     * @param resource admitted opened resource
     * @param lease file-handle owner observing both response writes
     */
    private static void writeResource(ChannelHandlerContext ctx, boolean keepAlive,
                                      StaticResourceResolver.Resource resource, HttpFileTransfers.Lease lease) {
        boolean transferred = false;
        DefaultFileRegion region = null;
        try {
            HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
            HttpUtil.setContentLength(response, resource.length());
            String type = URLConnection.guessContentTypeFromName(resource.name());
            response.headers().set(CONTENT_TYPE, type == null ? "application/octet-stream" : type);
            setCacheHeaders(response, resource);
            if (keepAlive) {
                response.headers().set(CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }
            ctx.write(response).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            region = new LeasedFileRegion(resource.channel(), resource.length());
            ChannelFuture transfer = ctx.write(region, ctx.newProgressivePromise());
            transferred = true;
            transfer.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            ChannelFuture finish = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            lease.watch(transfer, finish);
            finish.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            if (!keepAlive) {
                finish.addListener(ChannelFutureListener.CLOSE);
            }
        } finally {
            if (!transferred) {
                if (region != null) {
                    region.release();
                }
            }
        }
    }

    /**
     * Creates cache headers entirely from metadata captured with the opened resource.
     *
     * @param response response receiving headers
     * @param resource opened file metadata
     */
    private static void setCacheHeaders(HttpResponse response, StaticResourceResolver.Resource resource) {
        Instant now = Instant.now();
        response.headers().set(DATE, DATES.format(now));
        response.headers().set(EXPIRES, DATES.format(now.plusSeconds(HTTP_CACHE_SECONDS)));
        response.headers().set(CACHE_CONTROL, "private, max-age=" + HTTP_CACHE_SECONDS);
        response.headers().set(LAST_MODIFIED, DATES.format(resource.modified().toInstant()));
    }

    /**
     * Completes a conditional request without transferring file content.
     *
     * @param ctx connection's event-loop context
     */
    private static void sendNotModified(ChannelHandlerContext ctx) {
        var response = new DefaultFullHttpResponse(HTTP_1_1, NOT_MODIFIED);
        response.headers().set(DATE, DATES.format(Instant.now()));
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Leaves filesystem closure to its lease's reserved worker capacity.
     */
    private static final class LeasedFileRegion extends DefaultFileRegion {

        /**
         * Handle borrowed from the transfer lease.
         */
        private final FileChannel borrowedFile;

        /**
         * Borrows the admitted file for the outbound pipeline.
         *
         * @param file lease-owned handle
         * @param length captured file size
         */
        private LeasedFileRegion(FileChannel file, long length) {
            super(file, 0, length);
            this.borrowedFile = file;
        }

        /**
         * Reports actual handle state without acquiring or closing a descriptor.
         *
         * @return whether the borrowed file remains open
         */
        @Override
        public boolean isOpen() {
            return borrowedFile.isOpen();
        }

        /**
         * Checks the already-open lease handle without opening files on an I/O loop.
         *
         * @throws IOException if the lease has already closed its handle
         */
        @Override
        public void open() throws IOException {
            if (!borrowedFile.isOpen()) {
                throw new java.nio.channels.ClosedChannelException();
            }
        }

        /**
         * Releases the region only; transfer completion or disconnection closes the lease.
         */
        @Override
        protected void deallocate() {
            // The lease retains ownership until both writes complete or the channel closes.
        }
    }

    /**
     * Serializes routing behind a pending open without moving response writes off-loop.
     */
    private final class ConnectionRequests {

        /**
         * Original I/O context used to close on saturation or routing failure.
         */
        private final ChannelHandlerContext context;

        /**
         * Bounded routing actions with explicit discard ownership.
         */
        private final Deque<PendingRequest> pending = new ArrayDeque<>();

        /**
         * An admitted filesystem operation is awaiting its I/O result application.
         */
        private boolean opening;

        /**
         * Terminal connection state; late results cannot resume routing.
         */
        private boolean closed;

        /**
         * Only restore reads if this file barrier paused an enabled auto-read flag.
         */
        private boolean pausedReads;

        /**
         * Installs one connection-close cleanup observer.
         *
         * @param context original request context
         */
        private ConnectionRequests(ChannelHandlerContext context) {
            this.context = context;
            context.channel().closeFuture().addListener(ignored -> {
                closed = true;
                PendingRequest request;
                while ((request = pending.pollFirst()) != null) {
                    request.discard.run();
                }
            });
        }

        /**
         * Runs immediately in order or queues behind the current open.
         *
         * @param action synchronous routing action
         * @param discard cleanup when the action cannot run
         */
        private void submit(Runnable action, Runnable discard) {
            if (closed) {
                discard.run();
            } else if (!opening) {
                run(new PendingRequest(action, discard));
            } else if (pending.size() >= transfers.limits().maximumPendingPerConnection() - 1) {
                discard.run();
                FlowControlMetrics.HTTP_FILE_TRANSFER_REJECTIONS.increment();
                context.close();
            } else {
                pending.addLast(new PendingRequest(action, discard));
            }
        }

        /**
         * Resumes queued routes until another file open establishes a barrier.
         */
        private void drain() {
            while (!closed && !opening && !pending.isEmpty()) {
                run(pending.removeFirst());
            }
            if (!closed && !opening && pausedReads) {
                pausedReads = false;
                context.channel().config().setAutoRead(true);
            }
        }

        /**
         * Pauses future socket reads while retaining bounded current-batch requests.
         */
        private void pauseReads() {
            if (context.channel().config().isAutoRead()) {
                pausedReads = true;
                context.channel().config().setAutoRead(false);
            }
        }

        /**
         * Applies routing failure to this connection after action-owned cleanup.
         *
         * @param request dequeued action
         */
        private void run(PendingRequest request) {
            try {
                request.action.run();
            } catch (Throwable failure) {
                context.fireExceptionCaught(failure);
                context.close();
            }
        }
    }

    /**
     * Pairs execution with cleanup for an action that has not started.
     */
    private static final class PendingRequest {

        /**
         * Synchronous action owning its cleanup once started.
         */
        private final Runnable action;

        /**
         * Cleanup for rejected or disconnected queued work.
         */
        private final Runnable discard;

        /**
         * Captures explicit execution and discard ownership.
         *
         * @param action action run in response order
         * @param discard action run only when discarded
         */
        private PendingRequest(Runnable action, Runnable discard) {
            this.action = action;
            this.discard = discard;
        }
    }

}
