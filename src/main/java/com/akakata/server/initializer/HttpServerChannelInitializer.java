package com.akakata.server.initializer;

import com.akakata.communication.OutboundBudget;
import com.akakata.communication.OutboundLimits;
import com.akakata.communication.impl.SocketMessageSender;
import com.akakata.handlers.HttpLifecycleHandler;
import com.akakata.handlers.HttpRequestHandler;
import com.akakata.handlers.TransportMetricsHandler;
import com.akakata.metrics.ServerMetrics;
import com.akakata.server.ConnectionAdmission;
import com.akakata.server.HttpAdmission;
import com.akakata.server.HttpLimits;
import com.akakata.server.TransportLimits;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;

import javax.inject.Inject;
import java.util.concurrent.TimeUnit;

/**
 * Installs HTTP codecs, request aggregation, chunked transfer support and request routing.
 *
 * @author Kyia
 */
public class HttpServerChannelInitializer extends ChannelInitializer<SocketChannel> {

    /**
     * Router for aggregated HTTP requests.
     */
    private final HttpRequestHandler httpRequestHandler;

    /**
     * Shared connection allowance for all listeners.
     */
    private final HttpAdmission admission;

    /**
     * HTTP request and inactivity policy.
     */
    private final HttpLimits limits;

    /**
     * Per-channel encoded write allowance.
     */
    private final OutboundLimits outboundLimits;

    /**
     * Aggregate write reservation owner shared with game sockets.
     */
    private final OutboundBudget budget;

    /**
     * Creates a http server channel initializer instance with the supplied dependencies.
     *
     * @param httpRequestHandler the http request handler
     */
    public HttpServerChannelInitializer(HttpRequestHandler httpRequestHandler) {
        this(httpRequestHandler, new ConnectionAdmission(TransportLimits.DEFAULT), HttpLimits.DEFAULT,
                OutboundLimits.DEFAULT, OutboundBudget.DEFAULT);
    }

    /**
     * Creates the HTTP pipeline with context-wide resource admission.
     *
     * @param httpRequestHandler the application HTTP router
     * @param admission          the shared connection owner
     * @param limits             the HTTP request policy
     * @param outboundLimits     the per-channel encoded allowance
     * @param budget             the aggregate write owner
     */
    @Inject
    public HttpServerChannelInitializer(HttpRequestHandler httpRequestHandler, ConnectionAdmission admission,
                                        HttpLimits limits, OutboundLimits outboundLimits, OutboundBudget budget) {
        this.httpRequestHandler = httpRequestHandler;
        this.admission = new HttpAdmission(admission, limits);
        this.limits = limits;
        this.outboundLimits = outboundLimits;
        this.budget = budget;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        // Create a default pipeline implementation.
        ChannelPipeline pipeline = ch.pipeline();
        SocketMessageSender.installOutboundBudget(pipeline, outboundLimits, budget);
        pipeline.addFirst("zeaTransportMetrics", new TransportMetricsHandler(ServerMetrics.getInstance()));
        if (limits.readIdleMillis() > 0) {
            pipeline.addLast(new IdleStateHandler(limits.readIdleMillis(), 0, 0, TimeUnit.MILLISECONDS));
        }
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpLifecycleHandler(admission));
        pipeline.addLast(new HttpObjectAggregator(limits.maximumRequestBytes()));
        pipeline.addLast(new ChunkedWriteHandler());
        pipeline.addLast(httpRequestHandler);
    }
}
