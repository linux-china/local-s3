package com.robothy.s3.rest.netty;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.robothy.netty.router.Router;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpObjectDecoder;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.util.concurrent.Executor;

/**
 * Initializes the channel pipeline of the LocalS3 HTTP server.
 *
 * <p>HTTP parsing, request aggregation, encoding and chunked writing run on the channel's event loop, so that
 * a connection stops reading, and its client sending, while a request is handled, instead of queuing the body
 * of the next request for a busy thread. Routing and handling run on {@code executor}, which all connections
 * share; see {@linkplain LocalS3HttpMessageHandler}.
 */
public class LocalS3ServerInitializer extends ChannelInitializer<SocketChannel> {

    private final Executor executor;

    private final Router router;

    private final XmlMapper xmlMapper;

    private final long maxRequestBodySize;

    private final long requestBodyFileThreshold;

    private final long idleConnectionTimeoutSeconds;

    private final int maxRequestHeaderSize;

    /**
     * Create a channel initializer.
     *
     * @param executor                     executes request handling, shared by all connections.
     * @param router                       routes requests to handlers.
     * @param xmlMapper                    renders S3 errors.
     * @param maxRequestBodySize           max request body size in bytes.
     * @param requestBodyFileThreshold     size in bytes above which a request body is buffered in a temporary file.
     * @param idleConnectionTimeoutSeconds seconds after which an idle connection is closed; {@code 0} never closes it.
     */
    public LocalS3ServerInitializer(Executor executor, Router router, XmlMapper xmlMapper,
                                    long maxRequestBodySize, long requestBodyFileThreshold,
                                    long idleConnectionTimeoutSeconds) {
        this(executor, router, xmlMapper, maxRequestBodySize, requestBodyFileThreshold, idleConnectionTimeoutSeconds,
                HttpObjectDecoder.DEFAULT_MAX_HEADER_SIZE);
    }

    /**
     * Create a channel initializer.
     *
     * @param executor                     executes request handling, shared by all connections.
     * @param router                       routes requests to handlers.
     * @param xmlMapper                    renders S3 errors.
     * @param maxRequestBodySize           max request body size in bytes.
     * @param requestBodyFileThreshold     size in bytes above which a request body is buffered in a temporary file.
     * @param idleConnectionTimeoutSeconds seconds after which an idle connection is closed; {@code 0} never closes it.
     * @param maxRequestHeaderSize         max size in bytes of the header section of a request.
     */
    public LocalS3ServerInitializer(Executor executor, Router router, XmlMapper xmlMapper,
                                    long maxRequestBodySize, long requestBodyFileThreshold,
                                    long idleConnectionTimeoutSeconds, int maxRequestHeaderSize) {
        this.maxRequestHeaderSize = maxRequestHeaderSize;
        this.executor = executor;
        this.router = router;
        this.xmlMapper = xmlMapper;
        this.maxRequestBodySize = maxRequestBodySize;
        this.requestBodyFileThreshold = requestBodyFileThreshold;
        this.idleConnectionTimeoutSeconds = idleConnectionTimeoutSeconds;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline()
                .addLast("http-request-decoder", new HttpRequestDecoder(new HttpDecoderConfig()
                        .setMaxHeaderSize(maxRequestHeaderSize)))
                .addLast("http-response-encoder", new HttpResponseEncoder())
                .addLast("chunked-writer", new ChunkedWriteHandler());
        if (idleConnectionTimeoutSeconds > 0) {
            ch.pipeline().addLast("idle-connection", new IdleConnectionHandler(idleConnectionTimeoutSeconds));
        }
        // The router of LocalS3 verifies the signature of a request before its body is received.
        RequestHeadVerifier headVerifier = router instanceof RequestHeadVerifier verifier ? verifier : RequestHeadVerifier.ACCEPT_ALL;
        ch.pipeline()
                .addLast("local-s3-request-decoder", new LocalS3HttpRequestDecoder(maxRequestBodySize,
                        requestBodyFileThreshold, xmlMapper, headVerifier))
                .addLast("local-s3-response-encoder", new LocalS3HttpResponseEncoder())
                .addLast("local-s3-message-handler", new LocalS3HttpMessageHandler(router, executor));
    }

}
