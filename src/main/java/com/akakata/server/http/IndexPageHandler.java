package com.akakata.server.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.util.CharsetUtil;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;

/**
 * @author Kelvin
 */
public class IndexPageHandler extends ApiHandler {

    protected static final String NEWLINE = "\r\n";

    public void createIndexPage(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        // Allow only GET methods.
        if (request.method() != GET) {
            sendError(ctx, FORBIDDEN);
            return;
        }

        // Send the demo page and favicon.ico
        sendHttpResponse(ctx, getContent());
    }

    protected ByteBuf getContent() {
        return Unpooled.copiedBuffer(
                "<html>" + NEWLINE +
                        "<head>" + NEWLINE +
                        "<title>Index</title>" + NEWLINE +
                        "</head>" + NEWLINE +
                        "<body>" + NEWLINE +
                        "</body>" + NEWLINE +
                        "</html>", CharsetUtil.UTF_8);
    }
}
