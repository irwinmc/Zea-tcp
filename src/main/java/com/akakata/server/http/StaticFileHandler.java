package com.akakata.server.http;

import com.akakata.server.HttpFileTransferLimits;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLConnection;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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
    private final StaticResourceResolver resolver;

    /**
     * Shared file-handle admission and response-completion policy.
     */
    private final HttpFileTransfers transfers;

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
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.transfers = Objects.requireNonNull(transfers, "transfers");
    }

    /**
     * Validates and admits a request, then transfers a safely opened file in write order.
     *
     * <p>Admission failure closes the connection before opening a file. The transfer
     * owner bounds handles and observes response completion independently of requests.
     *
     * @param ctx     connection's event-loop context
     * @param request borrowed HTTP request
     * @throws Exception if HTTP presentation fails unexpectedly
     */
    public void handleStaticFile(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        if (!request.decoderResult().isSuccess()) {
            sendError(ctx, BAD_REQUEST);
            return;
        }
        if (request.method() != GET) {
            sendError(ctx, METHOD_NOT_ALLOWED);
            return;
        }
        Instant modifiedSince = null;
        String header = request.headers().get(IF_MODIFIED_SINCE);
        if (header != null && !header.isBlank()) {
            try {
                modifiedSince = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(header));
            } catch (DateTimeParseException invalid) {
                sendError(ctx, BAD_REQUEST);
                return;
            }
        }
        HttpFileTransfers.Lease lease = transfers.tryAcquire(ctx.channel());
        if (lease == null) {
            // Close without inserting an error response into a pending pipelined body.
            ctx.close();
            return;
        }
        boolean monitored = false;
        try {
            StaticResourceResolver.Resource resource;
            try {
                resource = resolver.open(request.uri());
            } catch (AccessDeniedException forbidden) {
                sendError(ctx, FORBIDDEN);
                return;
            } catch (NoSuchFileException missing) {
                sendError(ctx, NOT_FOUND);
                return;
            } catch (FileSystemException rejected) {
                sendError(ctx, FORBIDDEN);
                return;
            } catch (IOException failure) {
                LOG.warn("Unable to open static resource", failure);
                sendError(ctx, INTERNAL_SERVER_ERROR);
                return;
            }
            lease.attach(resource.channel());
            if (modifiedSince != null && resource.modified().toInstant().getEpochSecond() <= modifiedSince.getEpochSecond()) {
                sendNotModified(ctx);
                return;
            }
            writeResource(ctx, request, resource, lease);
            monitored = true;
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
     * @param request request providing keep-alive policy
     * @param resource admitted opened resource
     * @param lease file-handle owner observing both response writes
     */
    private static void writeResource(ChannelHandlerContext ctx, FullHttpRequest request,
                                      StaticResourceResolver.Resource resource, HttpFileTransfers.Lease lease) {
        boolean transferred = false;
        DefaultFileRegion region = null;
        try {
            HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
            HttpUtil.setContentLength(response, resource.length());
            String type = URLConnection.guessContentTypeFromName(resource.name());
            response.headers().set(CONTENT_TYPE, type == null ? "application/octet-stream" : type);
            setCacheHeaders(response, resource);
            if (HttpUtil.isKeepAlive(request)) {
                response.headers().set(CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }
            ctx.write(response).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            region = new DefaultFileRegion(resource.channel(), 0, resource.length());
            ChannelFuture transfer = ctx.write(region, ctx.newProgressivePromise());
            transferred = true;
            transfer.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            ChannelFuture finish = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
            lease.watch(transfer, finish);
            finish.addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            if (!HttpUtil.isKeepAlive(request)) {
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
}
