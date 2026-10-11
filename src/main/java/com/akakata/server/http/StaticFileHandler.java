package com.akakata.server.http;

import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.IF_MODIFIED_SINCE;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Serves files under the configured static root with MIME types and HTTP cache headers.
 *
 * <p>Paths are decoded and normalized before use. File-transfer listeners report
 * progress and close open files when the asynchronous transfer completes.
 *
 * @author Kelvin
 */
public class StaticFileHandler {

    /**
     * Creates a static file handler instance.
     */
    public StaticFileHandler() {
    }

    /**
     * HTTP date pattern used for cache response headers.
     */
    public static final String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz";

    /**
     * Time-zone identifier used for HTTP date formatting.
     */
    public static final String HTTP_DATE_GMT_TIMEZONE = "GMT";

    /**
     * Static-resource cache lifetime in seconds.
     */
    public static final int HTTP_CACHE_SECONDS = 60;

    /**
     * Static-resource directory relative to the working directory.
     */
    public static final String HTML_FILE_DIR = "html";

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(StaticFileHandler.class);

    /**
     * Normalized static-resource root path.
     */
    private static final Path HTML_BASE_PATH =
            Paths.get(System.getProperty("user.dir")).resolve(HTML_FILE_DIR).normalize();

    /**
     * Per-thread HTTP date formatter.
     */
    private static final ThreadLocal<SimpleDateFormat> DATE_FORMATTER = ThreadLocal.withInitial(() -> {
        SimpleDateFormat sdf = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE));
        return sdf;
    });

    /**
     * Validates a static-file request, applies cache headers and starts an asynchronous transfer.
     *
     * @param ctx the channel handler context
     * @param request the full HTTP request
     * @throws Exception if file access or transfer setup fails
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

        final String uri = request.uri();
        final Path path = sanitizeUri(uri);
        if (path == null) {
            sendError(ctx, FORBIDDEN);
            return;
        }

        if (Files.notExists(path) || !Files.isRegularFile(path)) {
            sendError(ctx, NOT_FOUND);
            return;
        }

        try {
            if (Files.isHidden(path)) {
                sendError(ctx, FORBIDDEN);
                return;
            }
        } catch (IOException e) {
            LOG.warn("Unable to determine hidden attribute for {}", path, e);
            sendError(ctx, FORBIDDEN);
            return;
        }

        // Cache Validation
        String ifModifiedSince = request.headers().get(IF_MODIFIED_SINCE);
        if (ifModifiedSince != null && !ifModifiedSince.isEmpty()) {
            Date ifModifiedSinceDate = DATE_FORMATTER.get().parse(ifModifiedSince);

            // Only compare up to the second because the datetime format we send to the client does not have milliseconds
            long ifModifiedSinceDateSeconds = ifModifiedSinceDate.getTime() / 1000;
            long fileLastModifiedSeconds = Files.getLastModifiedTime(path).toMillis() / 1000;
            if (ifModifiedSinceDateSeconds == fileLastModifiedSeconds) {
                sendNotModified(ctx);
                return;
            }
        }

        RandomAccessFile raf;
        try {
            raf = new RandomAccessFile(path.toFile(), "r");
        } catch (IOException e) {
            sendError(ctx, NOT_FOUND);
            return;
        }

        long fileLength = raf.length();
        HttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
        HttpUtil.setContentLength(response, fileLength);
        setContentTypeHeader(response, path);
        setDateAndCacheHeader(response, path);
        if (HttpUtil.isKeepAlive(request)) {
            response.headers().set(CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        }

        // Write the initial line and the header.
        ctx.write(response);

        ChannelFuture sendFileFuture = ctx.write(new DefaultFileRegion(raf.getChannel(), 0, fileLength), ctx.newProgressivePromise());
        sendFileFuture.addListener(new FileTransferProgressListener());
        sendFileFuture.addListener(new FileCloseListener(raf, path));

        // Write the end marker
        ChannelFuture lastContentFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);

        // Decide whether to close th connection or not
        if (!HttpUtil.isKeepAlive(request)) {
            // Close the connection when the whole content is written out.
            lastContentFuture.addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * Decodes and resolves a URI path within the configured static root.
     *
     * @param uri the request URI to sanitize
     * @return the resolved path, or {@code null} if the URI is rejected
     */
    private Path sanitizeUri(String uri) {
        // Decode the path.
        String decoded = URLDecoder.decode(uri, StandardCharsets.UTF_8);
        if (decoded.isEmpty() || decoded.charAt(0) != '/') {
            return null;
        }

        if (decoded.equals("/")) {
            return null;
        }

        try {
            Path resolved = HTML_BASE_PATH.resolve(decoded.substring(1)).normalize();
            if (!resolved.startsWith(HTML_BASE_PATH)) {
                return null;
            }
            return resolved;
        } catch (InvalidPathException ex) {
            LOG.warn("Invalid static resource path: {}", decoded, ex);
            return null;
        }
    }

    /**
     * Writes a plain-text file-serving error response and closes the channel.
     *
     * @param ctx channel handler context
     * @param status HTTP response status
     */
    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Writes an HTTP 304 response for a cached resource.
     *
     * @param ctx channel handler context
     */
    private void sendNotModified(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.NOT_MODIFIED);
        response.headers().set(HttpHeaderNames.DATE, DATE_FORMATTER.get().format(new Date()));
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Adds date, expiration, cache-control and last-modified headers for a file.
     *
     * @param response HTTP response
     * @param fileToCache file to cache
     * @throws IOException if a required resource cannot be read or decoded
     */
    private void setDateAndCacheHeader(HttpResponse response, Path fileToCache) throws IOException {
        SimpleDateFormat dateFormatter = DATE_FORMATTER.get();
        Date now = new Date();

        // Date header
        response.headers().set(HttpHeaderNames.DATE, dateFormatter.format(now));

        // Cache headers
        Calendar expiresTime = new GregorianCalendar();
        expiresTime.setTime(now);
        expiresTime.add(Calendar.SECOND, HTTP_CACHE_SECONDS);
        response.headers().set(HttpHeaderNames.EXPIRES, dateFormatter.format(expiresTime.getTime()));
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "private, max-age=" + HTTP_CACHE_SECONDS);
        response.headers().set(HttpHeaderNames.LAST_MODIFIED,
                dateFormatter.format(new Date(Files.getLastModifiedTime(fileToCache).toMillis())));
    }

    /**
     * Sets the response content type from the file name and MIME mapping.
     *
     * @param res the HTTP response
     * @param file the file to determine content type for
     */
    private void setContentTypeHeader(HttpResponse res, Path file) {
        String contentType = null;
        try {
            contentType = Files.probeContentType(file);
        } catch (IOException e) {
            LOG.debug("Unable to detect content type for {}", file, e);
        }
        if (contentType == null) {
            contentType = "application/octet-stream";
        }
        res.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
    }

    /**
     * Logs asynchronous file-transfer progress and completion.
     */
    private static class FileTransferProgressListener implements ChannelProgressiveFutureListener {

        /**
         * Creates a file transfer progress listener instance.
         */
        private FileTransferProgressListener() {
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void operationProgressed(ChannelProgressiveFuture future, long progress, long total) {
            if (total < 0) {
                LOG.debug("{} Transfer progress: {}", future.channel(), progress);
            } else {
                LOG.debug("{} Transfer progress: {} / {}", future.channel(), progress, total);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void operationComplete(ChannelProgressiveFuture future) {
            LOG.debug("{} Transfer complete.", future.channel());
        }
    }

    /**
     * Closes the file handle after its asynchronous channel transfer completes.
     *
     * @param raf the open file handle
     * @param path the transferred file path
     */
    private record FileCloseListener(RandomAccessFile raf, Path path) implements ChannelFutureListener {

        /**
         * {@inheritDoc}
         */
        @Override
        public void operationComplete(ChannelFuture future) {
            try {
                raf.close();
            } catch (IOException e) {
                LOG.warn("Failed to close file resource {}", path, e);
            }
        }
    }
}
