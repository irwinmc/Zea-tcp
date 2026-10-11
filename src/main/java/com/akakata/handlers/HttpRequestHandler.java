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
 * Routes full HTTP requests to API, RPC, health, metrics, index or static-file handlers.
 *
 * <p>Request paths are normalized before routing. OPTIONS requests receive the
 * configured cross-origin response.
 *
 * @author Kelvin
 */
@Sharable
public class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    /**
     * Logger for component lifecycle and diagnostic messages.
     */
    private static final Logger LOG = LoggerFactory.getLogger(HttpRequestHandler.class);

    /**
     * Route prefix for root requests.
     */
    private static final String ROOT = "/";

    /**
     * Route prefix for api requests.
     */
    private static final String API = "/api";

    /**
     * Route prefix for rpc requests.
     */
    private static final String RPC = "/r";

    /**
     * Route prefix for health requests.
     */
    private static final String HEALTH = "/health";

    /**
     * Route prefix for metrics requests.
     */
    private static final String METRICS = "/metrics";

    /**
     * Route prefix for static file handler requests.
     */
    private final StaticFileHandler staticFileHandler;

    /**
     * Route prefix for index page handler requests.
     */
    private final IndexPageHandler indexPageHandler;

    /**
     * Route prefix for api handler requests.
     */
    private final ApiHandler apiHandler;

    /**
     * Route prefix for rpc handler requests.
     */
    private final RpcHandler rpcHandler;

    /**
     * Route prefix for health check handler requests.
     */
    private final HealthCheckHandler healthCheckHandler;

    /**
     * Route prefix for metrics handler requests.
     */
    private final MetricsHandler metricsHandler;

    /**
     * Creates a http request handler instance with the supplied dependencies.
     *
     * @param staticFileHandler the static file handler
     * @param indexPageHandler the index page handler
     * @param apiHandler the api handler
     * @param rpcHandler the rpc handler
     * @param healthCheckHandler the health check handler
     * @param metricsHandler the metrics handler
     */
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

    /**
     * {@inheritDoc}
     */
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

    /**
     * {@inheritDoc}
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LOG.warn("Channel {} has thrown exception {}.", ctx.channel(), cause);
        // e.getCause().printStackTrace();
        ctx.channel().close();
    }

    /**
     * Writes the response for a CORS preflight request.
     *
     * @param ctx channel handler context
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

    /**
     * Extracts and normalizes the URI path for endpoint selection.
     *
     * @param rawUri the raw request URI
     * @return the normalized request path
     */
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

    /**
     * Extracts the first segment used for handler routing.
     *
     * @param path the resource or file path
     * @return the base path segment
     */
    private static String baseSegment(String path) {
        if (ROOT.equals(path)) return ROOT;
        int idx = path.indexOf('/', 1);
        return (idx < 0) ? path : path.substring(0, idx);
    }
}
