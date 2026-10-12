package com.akakata.server.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.util.CharsetUtil;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;

/**
 * Loads and serves the bundled server index page.
 *
 * @author Kelvin
 */
public class IndexPageHandler extends AbstractHttpHandler {

    /**
     * Creates an index page handler instance.
     */
    public IndexPageHandler() {
    }

    /**
     * Line separator for HTML content.
     */
    protected static final String NEWLINE = "\r\n";

    /**
     * Serves the bundled index page as an HTTP response.
     *
     * @param ctx     the channel handler context
     * @param request the full HTTP request
     * @throws Exception if loading or serving the page fails
     */
    public void createIndexPage(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        // Allow only GET methods.
        if (request.method() != GET) {
            sendError(ctx, FORBIDDEN);
            return;
        }

        // Send the demo page and favicon.ico
        ByteBuf content = getContent();
        try {
            sendHttpResponse(ctx, content);
        } finally {
            content.release();
        }
    }

    /**
     * Loads the index resource into a native buffer.
     *
     * @return the caller-owned page-content buffer
     */
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
