package com.akakata.server.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;

/**
 * Handler for processing Remote Procedure Call (RPC) requests over HTTP.
 * <p>
 * This handler is designed to handle RPC-style API calls. It automatically includes
 * CORS (Cross-Origin Resource Sharing) headers in error responses to support
 * cross-domain requests from web browsers.
 * </p>
 * <p>
 * By default, all requests receive a FORBIDDEN (403) response with CORS headers enabled.
 * Override {@link #handleRpcRequest} to implement actual RPC logic.
 * </p>
 * <p>
 * <b>Usage Example:</b>
 * <pre>
 * public class MyRpcHandler extends RpcHandler {
 *     &#64;Override
 *     public void handleRpcRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
 *         // Parse RPC request
 *         String method = extractMethod(request);
 *         Object result = invokeMethod(method);
 *
 *         // Send RPC response with CORS
 *         ByteBuf response = Unpooled.copiedBuffer(toJson(result), CharsetUtil.UTF_8);
 *         sendHttpResponse(ctx, response);
 *     }
 * }
 * </pre>
 * </p>
 *
 * @author Kelvin
 */
public class RpcHandler extends AbstractHttpHandler {

    /**
     * Handles RPC requests. Override this method to implement RPC logic.
     * <p>
     * Default implementation returns HTTP 403 FORBIDDEN with CORS headers enabled.
     * </p>
     *
     * @param ctx     the channel handler context
     * @param request the full HTTP request containing RPC call data
     */
    public void handleRpcRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        sendError(ctx, FORBIDDEN, true);
    }
}
