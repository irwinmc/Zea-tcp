package com.akakata.server.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.util.CharsetUtil;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;

/**
 * Handler for serving the application's index (home) page.
 * <p>
 * This handler responds to HTTP GET requests with a simple HTML page.
 * Only GET method is allowed; other HTTP methods will receive a 403 FORBIDDEN response.
 * </p>
 * <p>
 * The default implementation returns a minimal HTML page. Override {@link #getContent()}
 * to customize the index page content.
 * </p>
 *
 * @author Kelvin
 */
public class IndexPageHandler extends AbstractHttpHandler {

    /** Line separator for HTML content */
    protected static final String NEWLINE = "\r\n";

    /**
     * Creates and sends the index page response.
     * <p>
     * Only GET requests are processed. Other HTTP methods will receive HTTP 403 FORBIDDEN.
     * </p>
     *
     * @param ctx     the channel handler context
     * @param request the full HTTP request
     * @throws Exception if an error occurs during page generation
     */
    public void createIndexPage(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        // Allow only GET methods.
        if (request.method() != GET) {
            sendError(ctx, FORBIDDEN);
            return;
        }

        // Send the demo page and favicon.ico
        sendHttpResponse(ctx, getContent());
    }

    /**
     * Generates the HTML content for the index page.
     * <p>
     * Override this method to customize the index page content.
     * </p>
     *
     * @return ByteBuf containing the HTML content
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
