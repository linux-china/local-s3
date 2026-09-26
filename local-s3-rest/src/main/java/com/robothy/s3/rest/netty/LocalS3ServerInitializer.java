package com.robothy.s3.rest.netty;

import com.robothy.netty.router.Router;
import com.robothy.s3.core.storage.HeapContent;
import com.robothy.s3.rest.LocalS3Config;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.ssl.OptionalSslHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.LongFunction;
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
 * <p>With {@linkplain LocalS3Config#tls() TLS} configured, a connection is decrypted by an {@code SslHandler}, which
 * the rest of the pipeline reads plain HTTP from. Unless {@linkplain LocalS3Config#tlsRequired() TLS is required}, the
 * port answers HTTP and HTTPS alike: an {@linkplain OptionalSslHandler} reads the first bytes of each connection and
 * inserts the {@code SslHandler} only for the connections that start with a TLS handshake, so a client that speaks
 * TLS and one that doesn't share the endpoint. {@linkplain ConnectionSchemes} records which of the two a connection
 * turned out to be, for the responses that name the URL of the service.
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
     * Creates the writers of the large request bodies that are received into the heap for the storage; {@code null} to
     * buffer them in files.
     */
    private final @Nullable LongFunction<Optional<HeapContent.Writer>> heapBodyWriters;

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
        this(config, executor, router, xmlMapper, requestBodyFileDirectory, inFlightRequests, requestRecorder, null);
    }

    /**
     * Create a channel initializer.
     *
     * @param config                   the configuration of the service, which limits the requests and connections.
     * @param executor                 executes request handling and receives the large request bodies, shared by all
     *                                 connections.
     * @param router                   routes requests to handlers.
     * @param xmlMapper                renders S3 errors.
     * @param requestBodyFileDirectory the directory that temporary request body files are created in, e.g. one on the
     *                                 file system of the storage, which then renames them into place; {@code null} for
     *                                 the default temporary directory.
     * @param inFlightRequests         counts the requests in flight of all connections, which a server that shuts down
     *                                 waits for.
     * @param requestRecorder          receives the requests of all connections once their responses are written.
     * @param heapBodyWriters          creates the writer of a large request body of the given expected length that is
     *                                 received into the heap for a storage that takes it over, e.g. the one of an
     *                                 {@code IN_MEMORY} service; {@code null} to buffer large bodies in files.
     */
    public LocalS3ServerInitializer(LocalS3Config config, Executor executor, Router router, XmlMapper xmlMapper,
                                    @Nullable Path requestBodyFileDirectory, InFlightRequests inFlightRequests,
                                    RequestRecorder requestRecorder,
                                    @Nullable LongFunction<Optional<HeapContent.Writer>> heapBodyWriters) {
        this.config = Objects.requireNonNull(config);
        this.executor = Objects.requireNonNull(executor);
        this.router = Objects.requireNonNull(router);
        this.xmlMapper = Objects.requireNonNull(xmlMapper);
        this.requestBodyFileDirectory = requestBodyFileDirectory;
        this.inFlightRequests = Objects.requireNonNull(inFlightRequests);
        this.requestRecorder = Objects.requireNonNull(requestRecorder);
        this.heapBodyWriters = heapBodyWriters;
        // Created once, as it parses the certificate and key; its engines are created per connection.
        this.sslContext = config.tls() == null ? null : config.tls().newServerSslContext();
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        if (sslContext != null && config.plainHttpAccepted()) {
            // Decided by the first bytes of the connection: a TLS handshake gets the SslHandler, anything else goes
            // to the HTTP decoder as it is.
            ConnectionSchemes.mixed(ch);
            ch.pipeline().addLast("optional-ssl", new SchemeDetectingSslHandler(sslContext));
        } else if (sslContext != null) {
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
                        config.requestBodyFileThreshold(), xmlMapper, headVerifier, requestBodyFileDirectory, executor,
                        heapBodyWriters))
                .addLast("local-s3-response-encoder", new LocalS3HttpResponseEncoder())
                .addLast("local-s3-message-handler", new LocalS3HttpMessageHandler(router, executor, inFlightRequests,
                        requestRecorder));
    }

    /**
     * An {@linkplain OptionalSslHandler} that records the scheme of the connection it decided on, so that the
     * responses which name the URL of the service, e.g. the {@code Location} of a browser form upload, name the one
     * the client actually used.
     */
    private static final class SchemeDetectingSslHandler extends OptionalSslHandler {

        SchemeDetectingSslHandler(SslContext sslContext) {
            super(sslContext);
        }

        @Override
        protected String newSslHandlerName() {
            return "ssl";
        }

        @Override
        protected SslHandler newSslHandler(ChannelHandlerContext context, SslContext sslContext) {
            ConnectionSchemes.https(context.channel());
            return super.newSslHandler(context, sslContext);
        }
    }

}
