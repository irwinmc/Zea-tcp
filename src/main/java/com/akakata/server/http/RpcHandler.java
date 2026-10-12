package com.akakata.server.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;

/**
 * Serves the placeholder HTTP RPC endpoint.
 *
 * @author Kelvin
 */
public class RpcHandler extends AbstractHttpHandler {

    /**
     * Creates a rpc handler instance.
     */
    public RpcHandler() {
    }

    /**
     * Writes the placeholder RPC response.
     *
     * @param ctx     the channel handler context
     * @param request the full HTTP request containing RPC call data
     */
    public void handleRpcRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        sendError(ctx, FORBIDDEN, true);
    }
}
