package com.akakata.server.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;

/**
 * Handler for processing RESTful API requests.
 * <p>
 * This handler is intended to be extended or modified to handle various API endpoints.
 * By default, it returns a FORBIDDEN (403) response for all requests.
 * </p>
 * <p>
 * <b>Usage Example:</b>
 * <pre>
 * public class MyApiHandler extends ApiHandler {
 *     &#64;Override
 *     public void handleApiRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
 *         String uri = request.uri();
 *         if ("/api/users".equals(uri)) {
 *             // Handle user API
 *             sendHttpResponse(ctx, Unpooled.copiedBuffer("{\"status\":\"ok\"}", CharsetUtil.UTF_8));
 *         } else {
 *             super.handleApiRequest(ctx, request);
 *         }
 *     }
 * }
 * </pre>
 * </p>
 *
 * @author Kelvin
 */
public class ApiHandler extends AbstractHttpHandler {

    /**
     * Handles API requests. Override this method to implement custom API logic.
     * <p>
     * Default implementation returns HTTP 403 FORBIDDEN for all requests.
     * </p>
     *
     * @param ctx     the channel handler context
     * @param request the full HTTP request
     */
    public void handleApiRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        sendError(ctx, FORBIDDEN);
    }
}
