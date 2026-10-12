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
 * Builds full HTTP success and error responses and writes them through Netty.
 *
 * <p>Success responses copy the supplied content into a response buffer. The caller
 * keeps ownership of the supplied content reference.
 *
 * @author Kelvin
 */
public abstract class AbstractHttpHandler {

    /**
     * Creates an abstract http handler instance.
     */
    public AbstractHttpHandler() {
    }

    /**
     * Writes a plain-text error response and closes the channel. The CORS overload adds
     * cross-origin headers when enabled.
     *
     * @param ctx    channel handler context
     * @param status HTTP response status
     */
    protected void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        sendError(ctx, status, false);
    }

    /**
     * Writes a plain-text error response and closes the channel. The CORS overload adds
     * cross-origin headers when enabled.
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
     * Writes an HTTP success response after copying the supplied content.
     *
     * <p>Copying advances the supplied buffer's reader index. The caller remains
     * responsible for releasing its original reference after this method returns.
     *
     * @param ctx     channel handler context
     * @param content caller-owned content copied into the response
     */
    protected void sendHttpResponse(ChannelHandlerContext ctx, ByteBuf content) {
        sendHttpResponse(ctx, content, "text/html; charset=UTF-8");
    }

    /**
     * Writes an HTTP success response after copying the supplied content.
     *
     * <p>Copying advances the supplied buffer's reader index. The caller remains
     * responsible for releasing its original reference after this method returns.
     *
     * @param ctx         channel handler context
     * @param content     caller-owned content copied into the response
     * @param contentType content type (e.g., "application/json; charset=UTF-8")
     */
    protected void sendHttpResponse(ChannelHandlerContext ctx, ByteBuf content, String contentType) {
        sendHttpResponse(ctx, content, contentType, OK);
    }

    /**
     * Copies caller-owned content into a status-specific response and closes after the write.
     *
     * <p>The caller always releases its source. A construction failure releases the
     * response locally; a completed handoff transfers response ownership to Netty.
     *
     * @param ctx         transport context
     * @param content     borrowed source advanced by copying
     * @param contentType response content type
     * @param status      HTTP response status
     */
    protected void sendHttpResponse(ChannelHandlerContext ctx, ByteBuf content, String contentType,
                                    HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status);
        boolean transferred = false;
        try {
            response.headers().set(CONTENT_TYPE, contentType);
            response.headers().set(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            response.headers().set(ACCESS_CONTROL_ALLOW_HEADERS, "Origin, X-Requested-With, Content-Type, Accept, Authorization");
            response.headers().set(ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
            response.headers().set(ACCESS_CONTROL_MAX_AGE, "3600");
            response.content().writeBytes(content);
            response.headers().setInt(CONTENT_LENGTH, response.content().readableBytes());
            var write = ctx.writeAndFlush(response);
            transferred = true;
            write.addListener(ChannelFutureListener.CLOSE);
        } finally {
            if (!transferred) {
                response.release();
            }
        }
    }
}
