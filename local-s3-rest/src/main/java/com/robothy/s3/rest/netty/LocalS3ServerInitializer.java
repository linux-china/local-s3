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

    private final long requestBodyFileThreshold;

    private final long idleConnectionTimeoutSeconds;

    /**
     * Create a channel initializer.
     *
     * @param executorGroup                executes request aggregation and handling.
     * @param router                       routes requests to handlers.
     * @param xmlMapper                    renders S3 errors.
     * @param maxRequestBodySize           max request body size in bytes.
     * @param requestBodyFileThreshold     size in bytes above which a request body is buffered in a temporary file.
     * @param idleConnectionTimeoutSeconds seconds after which an idle connection is closed; {@code 0} never closes it.
     */
    public LocalS3ServerInitializer(EventExecutorGroup executorGroup, Router router, XmlMapper xmlMapper,
                                    long maxRequestBodySize, long requestBodyFileThreshold,
                                    long idleConnectionTimeoutSeconds) {
        this.executorGroup = executorGroup;
        this.router = router;
        this.xmlMapper = xmlMapper;
        this.maxRequestBodySize = maxRequestBodySize;
        this.requestBodyFileThreshold = requestBodyFileThreshold;
        this.idleConnectionTimeoutSeconds = idleConnectionTimeoutSeconds;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
                .addLast("http-request-decoder", new HttpRequestDecoder())
                .addLast("http-response-encoder", new HttpResponseEncoder())
                .addLast("chunked-writer", new ChunkedWriteHandler());
        if (idleConnectionTimeoutSeconds > 0) {
            ch.pipeline().addLast("idle-connection", new IdleConnectionHandler(idleConnectionTimeoutSeconds));
        }
        // The router of LocalS3 verifies the signature of a request before its body is received.
        RequestHeadVerifier headVerifier = router instanceof RequestHeadVerifier verifier ? verifier : RequestHeadVerifier.ACCEPT_ALL;
        ch.pipeline()
                .addLast(executorGroup, "local-s3-request-decoder", new LocalS3HttpRequestDecoder(maxRequestBodySize,
                        requestBodyFileThreshold, xmlMapper, headVerifier))
                .addLast(executorGroup, "local-s3-response-encoder", new LocalS3HttpResponseEncoder())
                .addLast(executorGroup, "local-s3-message-handler", new LocalS3HttpMessageHandler(router));
    }

}
