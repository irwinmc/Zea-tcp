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

/**
 * @author Kelvin
 */
@Sharable
public class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger LOG = LoggerFactory.getLogger(HttpRequestHandler.class);

    /**
     * Root path
     */
    private static String rootPath = "/";

    /**
     * Open HTTP API(interface)
     */
    private static String apiPath = "/api";

    /**
     * Private RPC
     */
    private static String rpcPath = "/r";

    /**
     * Used to handle static file access requests.
     */
    private StaticFileHandler staticFileHandler = new StaticFileHandler();

    /**
     * Handle the server api path request and status
     */
    private IndexPageHandler indexPageHandler;
    private ApiHandler apiHandler;
    private RpcHandler rpcHandler;

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

    public IndexPageHandler getIndexPageHandler() {
        return indexPageHandler;
    }

    public void setIndexPageHandler(IndexPageHandler indexPageHandler) {
        this.indexPageHandler = indexPageHandler;
    }

    public ApiHandler getApiHandler() {
        return apiHandler;
    }

    public void setApiHandler(ApiHandler apiHandler) {
        this.apiHandler = apiHandler;
    }

    public RpcHandler getRpcHandler() {
        return rpcHandler;
    }

    public void setRpcHandler(RpcHandler rpcHandler) {
        this.rpcHandler = rpcHandler;
    }
}
