package com.akakata.handlers;

import com.akakata.server.http.*;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpMethod.OPTIONS;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * @author Kelvin
 */
@Sharable
public class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger LOG = LoggerFactory.getLogger(HttpRequestHandler.class);

    private static final String ROOT = "/";
    private static final String API = "/api";
    private static final String RPC = "/r";
    private static final String HEALTH = "/health";
    private static final String METRICS = "/metrics";

    private final StaticFileHandler staticFileHandler;
    private final IndexPageHandler indexPageHandler;
    private final ApiHandler apiHandler;
    private final RpcHandler rpcHandler;
    private final HealthCheckHandler healthCheckHandler;
    private final MetricsHandler metricsHandler;

    @Inject
    public HttpRequestHandler(StaticFileHandler staticFileHandler,
                              IndexPageHandler indexPageHandler,
                              ApiHandler apiHandler,
                              RpcHandler rpcHandler,
                              HealthCheckHandler healthCheckHandler,
                              MetricsHandler metricsHandler) {
        this.staticFileHandler = staticFileHandler;
        this.indexPageHandler = indexPageHandler;
        this.apiHandler = apiHandler;
        this.rpcHandler = rpcHandler;
        this.healthCheckHandler = healthCheckHandler;
        this.metricsHandler = metricsHandler;
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) throws Exception {
        // Handle OPTIONS preflight requests for CORS
        if (request.method() == OPTIONS) {
            handleOptionsRequest(ctx, request);
            return;
        }

        // Get base URI, below this gateway, the request can be handled as API request
        String path = normalizePath(request.uri());
        String base = baseSegment(path);

        // Index page
        if (ROOT.equals(path)) {
            indexPageHandler.createIndexPage(ctx, request);
            return;
        }

        // Health check endpoints - match exact path or subpaths
        if (HEALTH.equals(path) || path.startsWith(HEALTH + "/")) {
            healthCheckHandler.handleHealthCheck(ctx, request);
            return;
        }

        // Metrics endpoints - match exact path or subpaths
        if (METRICS.equals(path) || path.startsWith(METRICS + "/")) {
            metricsHandler.handleMetrics(ctx, request);
            return;
        }

        if (API.equals(base)) {
            apiHandler.handleApiRequest(ctx, request);
            return;
        }
        if (RPC.equals(base)) {
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

    /**
     * Handle CORS preflight OPTIONS request.
     *
     * @param ctx     channel handler context
     * @param request HTTP OPTIONS request
     */
    private void handleOptionsRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
        FullHttpResponse response = new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.EMPTY_BUFFER);

        // CORS headers
        response.headers().set(ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
        response.headers().set(ACCESS_CONTROL_ALLOW_HEADERS, "Origin, X-Requested-With, Content-Type, Accept, Authorization");
        response.headers().set(ACCESS_CONTROL_MAX_AGE, "3600");
        response.headers().set(CONTENT_LENGTH, 0);

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static String normalizePath(String rawUri) {
        // Remove fragment
        int hash = rawUri.indexOf('#');
        if (hash >= 0) {
            rawUri = rawUri.substring(0, hash);
        }

        // Remove query
        int q = rawUri.indexOf('?');
        if (q >= 0) {
            rawUri = rawUri.substring(0, q);
        }

        // Empty -> "/"
        if (rawUri.isEmpty()) {
            return ROOT;
        }

        // 统一前导斜杠
        if (rawUri.charAt(0) != '/') {
            rawUri = "/" + rawUri;
        }

        // 去除多余的结尾斜杠（但保留根路径 "/"）
        if (rawUri.length() > 1 && rawUri.endsWith("/")) {
            rawUri = rawUri.substring(0, rawUri.length() - 1);
        }

        return rawUri;
    }

    private static String baseSegment(String path) {
        if (ROOT.equals(path)) return ROOT;
        int idx = path.indexOf('/', 1);
        return (idx < 0) ? path : path.substring(0, idx);
    }
}
