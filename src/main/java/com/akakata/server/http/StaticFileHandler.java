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
 * Handles static file serving with zero-copy file transfer.
 * Does not extend AbstractHttpHandler because it uses Netty's FileRegion
 * for efficient large file transmission instead of in-memory ByteBuf.
 *
 * @author Kelvin
 */
public class StaticFileHandler {

    public static final String HTTP_DATE_FORMAT = "EEE, dd MMM yyyy HH:mm:ss zzz";
    public static final String HTTP_DATE_GMT_TIMEZONE = "GMT";
    public static final int HTTP_CACHE_SECONDS = 60;
    public static final String HTML_FILE_DIR = "html";
    private static final Logger LOG = LoggerFactory.getLogger(StaticFileHandler.class);
    private static final Path HTML_BASE_PATH =
            Paths.get(System.getProperty("user.dir")).resolve(HTML_FILE_DIR).normalize();

    /**
     * Thread-local SimpleDateFormat to avoid repeated creation and ensure thread safety.
     */
    private static final ThreadLocal<SimpleDateFormat> DATE_FORMATTER = ThreadLocal.withInitial(() -> {
        SimpleDateFormat sdf = new SimpleDateFormat(HTTP_DATE_FORMAT, Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone(HTTP_DATE_GMT_TIMEZONE));
        return sdf;
    });

    /**
     * Handles static file serving requests with zero-copy file transfer.
     * <p>
     * This method processes HTTP GET requests for static files, supporting:
     * <ul>
     *   <li>Path validation and security checks (prevents directory traversal)</li>
     *   <li>HTTP cache validation (If-Modified-Since header)</li>
     *   <li>MIME type detection</li>
     *   <li>Zero-copy file transfer using Netty's FileRegion</li>
     *   <li>Progress monitoring for large file transfers</li>
     * </ul>
     * </p>
     *
     * @param ctx     the channel handler context
     * @param request the full HTTP request
     * @throws Exception if an error occurs during file serving
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
     * Sanitizes and validates the request URI to prevent security vulnerabilities.
     * <p>
     * This method performs the following security checks:
     * <ul>
     *   <li>URL decoding</li>
     *   <li>Path normalization</li>
     *   <li>Directory traversal prevention (ensures path stays within HTML_BASE_PATH)</li>
     *   <li>Root path rejection</li>
     * </ul>
     * </p>
     *
     * @param uri the request URI to sanitize
     * @return the validated Path, or null if the URI is invalid or unsafe
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
     * Send HTTP error response for static file requests.
     *
     * @param ctx    channel handler context
     * @param status HTTP response status
     */
    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * When file timestamp is the same as what the browser is sending up, send a "304 Not Modified"
     *
     * @param ctx channel handler context
     */
    private void sendNotModified(ChannelHandlerContext ctx) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.NOT_MODIFIED);
        response.headers().set(HttpHeaderNames.DATE, DATE_FORMATTER.get().format(new Date()));
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Set the Date and Cache headers for the HTTP response.
     *
     * @param response    HTTP response
     * @param fileToCache file to cache
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
     * Sets the Content-Type header based on file extension.
     * <p>
     * Uses Java NIO's {@link Files#probeContentType} to automatically detect
     * the MIME type. Falls back to "application/octet-stream" if detection fails.
     * </p>
     *
     * @param res  the HTTP response
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
     * Progress listener for monitoring file transfer operations.
     * <p>
     * Logs transfer progress at DEBUG level. Useful for monitoring large file downloads.
     * </p>
     */
    private static class FileTransferProgressListener implements ChannelProgressiveFutureListener {
        @Override
        public void operationProgressed(ChannelProgressiveFuture future, long progress, long total) {
            if (total < 0) {
                LOG.debug("{} Transfer progress: {}", future.channel(), progress);
            } else {
                LOG.debug("{} Transfer progress: {} / {}", future.channel(), progress, total);
            }
        }

        @Override
        public void operationComplete(ChannelProgressiveFuture future) {
            LOG.debug("{} Transfer complete.", future.channel());
        }
    }

    /**
     * Listener to ensure file resources are properly closed after transfer completes.
     * <p>
     * Automatically closes the RandomAccessFile to prevent resource leaks.
     * </p>
     */
    private record FileCloseListener(RandomAccessFile raf, Path path) implements ChannelFutureListener {

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
