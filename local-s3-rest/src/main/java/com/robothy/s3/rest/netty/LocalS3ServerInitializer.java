package com.robothy.s3.rest.netty;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.robothy.netty.router.Router;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.concurrent.EventExecutorGroup;

/**
 * Initializes the channel pipeline of the LocalS3 HTTP server.
 *
 * <p>HTTP parsing, encoding and chunked writing run on the channel's event loop; request aggregation,
 * routing and handling run on {@code executorGroup}.
 */
public class LocalS3ServerInitializer extends ChannelInitializer<SocketChannel> {

    private final EventExecutorGroup executorGroup;

    private final Router router;

    private final XmlMapper xmlMapper;

    private final long maxRequestBodySize;

    /**
     * Create a channel initializer.
     *
     * @param executorGroup      executes request aggregation and handling.
     * @param router             routes requests to handlers.
     * @param xmlMapper          renders S3 errors.
     * @param maxRequestBodySize max request body size in bytes.
     */
    public LocalS3ServerInitializer(EventExecutorGroup executorGroup, Router router, XmlMapper xmlMapper,
                                    long maxRequestBodySize) {
        this.executorGroup = executorGroup;
        this.router = router;
        this.xmlMapper = xmlMapper;
        this.maxRequestBodySize = maxRequestBodySize;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
                .addLast("http-request-decoder", new HttpRequestDecoder())
                .addLast("http-response-encoder", new HttpResponseEncoder())
                .addLast("chunked-writer", new ChunkedWriteHandler())
                .addLast(executorGroup, "local-s3-request-decoder", new LocalS3HttpRequestDecoder(maxRequestBodySize, xmlMapper))
                .addLast(executorGroup, "local-s3-response-encoder", new LocalS3HttpResponseEncoder())
                .addLast(executorGroup, "local-s3-message-handler", new LocalS3HttpMessageHandler(router));
    }

}
