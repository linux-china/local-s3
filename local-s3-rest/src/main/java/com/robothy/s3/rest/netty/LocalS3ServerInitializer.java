package com.robothy.s3.rest.netty;

import com.robothy.netty.router.Router;
import com.robothy.s3.rest.LocalS3Config;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Initializes the channel pipeline of the LocalS3 HTTP server.
 *
 * <p>HTTP parsing, request aggregation, encoding and chunked writing run on the channel's event loop, so that
 * a connection stops reading, and its client sending, while a request is handled, instead of queuing the body
 * of the next request for a busy thread. Routing and handling run on {@code executor}, which all connections
 * share; see {@linkplain LocalS3HttpMessageHandler}. So do the writes of the request bodies that are buffered in
 * temporary files, so that an event loop never waits for the disk; see {@linkplain LocalS3HttpRequestDecoder}.
 *
 * <p>With {@linkplain LocalS3Config#tls() TLS} configured, every connection starts with an {@code SslHandler}, which the
 * rest of the pipeline reads plain HTTP from.
 */
public class LocalS3ServerInitializer extends ChannelInitializer<SocketChannel> {

    private final LocalS3Config config;

    private final Executor executor;

    private final Router router;

    private final XmlMapper xmlMapper;

    private final Path requestBodyFileDirectory;

    private final InFlightRequests inFlightRequests;

    private final RequestRecorder requestRecorder;

    /**
     * The context that the connections are encrypted with; {@code null} to serve plain HTTP.
     */
    private final @Nullable SslContext sslContext;

    /**
     * Create a channel initializer.
     *
     * @param config                   the configuration of the service, which limits the requests and connections.
     * @param executor                 executes request handling and writes the temporary request body files, shared
     *                                 by all connections.
     * @param router                   routes requests to handlers.
     * @param xmlMapper                renders S3 errors.
     * @param requestBodyFileDirectory the directory that temporary request body files are created in, e.g. one on the
     *                                 file system of the storage, which then renames them into place; {@code null} for
     *                                 the default temporary directory.
     * @param inFlightRequests         counts the requests in flight of all connections, which a server that shuts down
     *                                 waits for.
     * @param requestRecorder          receives the requests of all connections once their responses are written.
     */
    public LocalS3ServerInitializer(LocalS3Config config, Executor executor, Router router, XmlMapper xmlMapper,
                                    @Nullable Path requestBodyFileDirectory, InFlightRequests inFlightRequests,
                                    RequestRecorder requestRecorder) {
        this.config = Objects.requireNonNull(config);
        this.executor = Objects.requireNonNull(executor);
        this.router = Objects.requireNonNull(router);
        this.xmlMapper = Objects.requireNonNull(xmlMapper);
        this.requestBodyFileDirectory = requestBodyFileDirectory;
        this.inFlightRequests = Objects.requireNonNull(inFlightRequests);
        this.requestRecorder = Objects.requireNonNull(requestRecorder);
        // Created once, as it parses the certificate and key; its engines are created per connection.
        this.sslContext = config.tls() == null ? null : config.tls().newServerSslContext();
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        if (sslContext != null) {
            ch.pipeline().addLast("ssl", sslContext.newHandler(ch.alloc()));
        }
        ch.pipeline()
                .addLast("http-request-decoder", new HttpRequestDecoder(new HttpDecoderConfig()
                        .setMaxHeaderSize(config.maxRequestHeaderSize())))
                .addLast("http-response-encoder", new HttpResponseEncoder())
                .addLast("chunked-writer", new ChunkedWriteHandler());
        if (config.idleConnectionTimeoutSeconds() > 0) {
            ch.pipeline().addLast("idle-connection", new IdleConnectionHandler(config.idleConnectionTimeoutSeconds()));
        }
        // The router of LocalS3 verifies the signature of a request before its body is received.
        RequestHeadVerifier headVerifier = router instanceof RequestHeadVerifier verifier ? verifier : RequestHeadVerifier.ACCEPT_ALL;
        ch.pipeline()
                .addLast("local-s3-request-decoder", new LocalS3HttpRequestDecoder(config.maxRequestBodySize(),
                        config.requestBodyFileThreshold(), xmlMapper, headVerifier, requestBodyFileDirectory, executor))
                .addLast("local-s3-response-encoder", new LocalS3HttpResponseEncoder())
                .addLast("local-s3-message-handler", new LocalS3HttpMessageHandler(router, executor, inFlightRequests,
                        requestRecorder));
    }

}
