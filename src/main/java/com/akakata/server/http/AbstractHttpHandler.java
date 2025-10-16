package com.akakata.server.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Abstract base class for HTTP handlers providing common response methods.
 * <p>
 * This base class provides utility methods for sending HTTP error responses and
 * simple content responses. It is designed for handlers that deal with small,
 * in-memory responses using {@link FullHttpResponse} and {@link ByteBuf}.
 * </p>
 * <p>
 * <b>Note:</b> This class is NOT suitable for large file transfers. For file serving,
 * use a dedicated handler with Netty's {@code FileRegion} for zero-copy transmission.
 * </p>
 *
 * @author Kelvin
 * @see io.netty.handler.codec.http.FullHttpResponse
 * @see io.netty.buffer.ByteBuf
 */
public abstract class AbstractHttpHandler {

    /**
     * Send HTTP error response and close connection.
     *
     * @param ctx    channel handler context
     * @param status HTTP response status
     */
    protected void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        sendError(ctx, status, false);
    }

    /**
     * Send HTTP error response with optional CORS headers.
     *
     * @param ctx        channel handler context
     * @param status     HTTP response status
     * @param enableCors whether to add CORS headers
     */
    protected void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, boolean enableCors) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HTTP_1_1,
                status,
                Unpooled.copiedBuffer("Failure: " + status + "\r\n", CharsetUtil.UTF_8)
        );
        response.headers().set(CONTENT_TYPE, "text/plain; charset=UTF-8");

        if (enableCors) {
            response.headers().set(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        }

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Send HTTP response with content and CORS headers.
     *
     * @param ctx     channel handler context
     * @param content response content
     */
    protected void sendHttpResponse(ChannelHandlerContext ctx, ByteBuf content) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK);
        response.headers().set(CONTENT_TYPE, "text/html; charset=UTF-8");
        response.headers().set(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(ACCESS_CONTROL_ALLOW_HEADERS, "Origin, X-Requested-With, Content-Type, Accept");
        response.headers().set(ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE");
        response.content().writeBytes(content);

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
