package com.akakata.server.http;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;

import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;

/**
 * Serves the placeholder HTTP API endpoint.
 *
 * @author Kelvin
 */
public class ApiHandler extends AbstractHttpHandler {

    /**
     * Creates an api handler instance.
     */
    public ApiHandler() {
    }

    /**
     * Writes the placeholder API response.
     *
     * @param ctx     the channel handler context
     * @param request the full HTTP request
     */
    public void handleApiRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        sendError(ctx, FORBIDDEN);
    }
}
