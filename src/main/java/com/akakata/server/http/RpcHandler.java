package com.akakata.server.http;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * @author Kelvin
 */
public class RpcHandler {

    /**
     * Need to override
     *
     * @param ctx
     * @param request
     */
    public void handleRpcRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        sendError(ctx, FORBIDDEN);
    }

    protected void sendError(ChannelHandlerContext ctx, HttpResponseStatus status) {
        String data = "Failure: " + status.toString() + "\r\n";

        // Create http response
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, status);
        response.headers().set(CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().set("Access-Control-Allow-Origin", "*");
        response.content().writeBytes(data.getBytes(CharsetUtil.UTF_8));

        // Close the connection as soon as the error message is sent
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
