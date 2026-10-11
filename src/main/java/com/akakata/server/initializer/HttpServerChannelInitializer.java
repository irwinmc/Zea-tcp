package com.akakata.server.initializer;

import com.akakata.handlers.HttpRequestHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;

import javax.inject.Inject;

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
     * Creates a http server channel initializer instance with the supplied dependencies.
     *
     * @param httpRequestHandler the http request handler
     */
    @Inject
    public HttpServerChannelInitializer(HttpRequestHandler httpRequestHandler) {
        this.httpRequestHandler = httpRequestHandler;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        // Create a default pipeline implementation.
        ChannelPipeline pipeline = ch.pipeline();

        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(65536));
        pipeline.addLast(new ChunkedWriteHandler());
        pipeline.addLast(httpRequestHandler);
    }
}
