package com.akakata.handlers;

import com.akakata.server.http.ApiHandler;
import com.akakata.server.http.IndexPageHandler;
import com.akakata.server.http.RpcHandler;
import com.akakata.server.http.StaticFileHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

/**
 * @author Kelvin
 */
@Sharable
public class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger LOG = LoggerFactory.getLogger(HttpRequestHandler.class);

    /**
     * Root path
     */
    private static final String rootPath = "/";

    /**
     * Open HTTP API(interface)
     */
    private static final String apiPath = "/api";

    /**
     * Private RPC
     */
    private static final String rpcPath = "/r";

    private final StaticFileHandler staticFileHandler;
    private final IndexPageHandler indexPageHandler;
    private final ApiHandler apiHandler;
    private final RpcHandler rpcHandler;

    @Inject
    public HttpRequestHandler(StaticFileHandler staticFileHandler,
                             IndexPageHandler indexPageHandler,
                             ApiHandler apiHandler,
                             RpcHandler rpcHandler) {
        this.staticFileHandler = staticFileHandler;
        this.indexPageHandler = indexPageHandler;
        this.apiHandler = apiHandler;
        this.rpcHandler = rpcHandler;
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        // Get base URI, below this gateway, the request can be handled as API request
        String baseUri = getBaseUri(request.uri());

        // Index page
        if (baseUri.equals(rootPath)) {
            // Hold the index page
            indexPageHandler.createIndexPage(ctx, request);
            return;
        }

        if (baseUri.equals(apiPath)) {
            apiHandler.handleApiRequest(ctx, request);
            return;
        }

        if (baseUri.equals(rpcPath)) {
            rpcHandler.handleRpcRequest(ctx, request);
            return;
        }

        // Server static file, as favicon.ico
        staticFileHandler.handleStaticFile(ctx, request);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOG.warn("Channel {} has thrown exception {}.", ctx.channel(), cause);
        // e.getCause().printStackTrace();
        ctx.channel().close();
    }

    private String getBaseUri(String uri) {
        int idx = uri.indexOf("/", 1);
        if (idx == -1) {
            return "/";
        } else {
            return uri.substring(0, idx);
        }
    }
}
