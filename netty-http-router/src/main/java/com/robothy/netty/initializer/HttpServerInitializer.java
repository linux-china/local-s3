package com.robothy.netty.initializer;

import com.robothy.netty.codec.HttpMessageHandler;
import com.robothy.netty.codec.RouterHttpRequestDecoder;
import com.robothy.netty.codec.RouterHttpResponseEncoder;
import com.robothy.netty.router.Router;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.EventExecutorGroup;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class HttpServerInitializer extends ChannelInitializer<SocketChannel> {

  /**
   * Default max length in bytes of the request line, larger than the 4 KB of Netty's default, which a presigned URL
   * with a session token, or a long URL-encoded path, easily exceeds.
   */
  public static final int DEFAULT_MAX_INITIAL_LINE_LENGTH = 16 * 1024;

  /**
   * Default max size in bytes of the header section of a request, larger than the 8 KB of Netty's default.
   */
  public static final int DEFAULT_MAX_HEADER_SIZE = 16 * 1024;

  /**
   * Max size in bytes of a request body chunk, larger than the 8 KB of Netty's default. Each chunk is a task for the
   * {@code executorGroup} and a component of the aggregated body, so a 64 MB upload is about 1000 of them rather
   * than 8000. A larger value doesn't help unless the receive buffer is larger: netty reads at most 64 KB at a time
   * by default ({@linkplain io.netty.channel.AdaptiveRecvByteBufAllocator#DEFAULT_MAXIMUM}).
   */
  public static final int DEFAULT_MAX_CHUNK_SIZE = 64 * 1024;

  /**
   * Default time after which a connection that has neither read nor written anything is closed, e.g. one that sent
   * half of a request, or stalled during an upload, and holds its partly aggregated body meanwhile.
   */
  public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(5);

  private final EventExecutorGroup executorGroup;

  private final Router router;

  private final int maxRequestBodySize;

  private final int maxInitialLineLength;

  private final int maxHeaderSize;

  public HttpServerInitializer(EventExecutorGroup executorGroup, Router router) {
    this(executorGroup, router, RouterHttpRequestDecoder.DEFAULT_MAX_REQUEST_BODY_SIZE);
  }

  /**
   * @param maxRequestBodySize max request body size in bytes, positive; a larger request is answered with
   *                           {@code 413 Request Entity Too Large}.
   */
  public HttpServerInitializer(EventExecutorGroup executorGroup, Router router, int maxRequestBodySize) {
    this(executorGroup, router, maxRequestBodySize, DEFAULT_MAX_INITIAL_LINE_LENGTH, DEFAULT_MAX_HEADER_SIZE);
  }

  /**
   * @param maxRequestBodySize   max request body size in bytes, positive; a larger request is answered with
   *                             {@code 413 Request Entity Too Large}.
   * @param maxInitialLineLength max length in bytes of the request line, positive.
   * @param maxHeaderSize        max size in bytes of the header section of a request, positive.
   */
  public HttpServerInitializer(EventExecutorGroup executorGroup, Router router, int maxRequestBodySize,
                               int maxInitialLineLength, int maxHeaderSize) {
    // Checked here rather than by the decoder, which is only created for each connection.
    if (maxRequestBodySize <= 0) {
      throw new IllegalArgumentException("maxRequestBodySize must be positive.");
    }
    if (maxInitialLineLength <= 0) {
      throw new IllegalArgumentException("maxInitialLineLength must be positive.");
    }
    if (maxHeaderSize <= 0) {
      throw new IllegalArgumentException("maxHeaderSize must be positive.");
    }
    this.executorGroup = executorGroup;
    this.router = router;
    this.maxRequestBodySize = maxRequestBodySize;
    this.maxInitialLineLength = maxInitialLineLength;
    this.maxHeaderSize = maxHeaderSize;
  }

  @Override
  protected void initChannel(SocketChannel ch) throws Exception {
    ChannelPipeline pipeline = ch.pipeline();
    Duration idleTimeout = idleTimeout();
    if (idleTimeout != null && idleTimeout.isPositive()) {
      pipeline.addLast("idle-state-handler", new IdleConnectionCloser(idleTimeout));
    }
    // Unlike a separate decoder and encoder, the codec pairs each response with its request, so the response to a HEAD
    // request keeps its Content-Length but not its body, which would be taken for the start of the next response.
    pipeline.addLast("http-server-codec", new HttpServerCodec(decoderConfig()));
    // Writes the chunked response bodies, e.g. static files, chunk by chunk as the connection accepts them. It runs on
    // the executor group, so that reading a file doesn't block the event loop.
    pipeline.addLast(this.executorGroup, "router-chunked-writer", new ChunkedWriteHandler());
    pipeline.addLast(this.executorGroup, "router-http-request-decoder", new RouterHttpRequestDecoder(maxRequestBodySize));
    pipeline.addLast(this.executorGroup, "router-http-response-encoder", new RouterHttpResponseEncoder());
    pipeline.addLast(this.executorGroup, "router-http-message-handler", new HttpMessageHandler(router));
  }

  /**
   * The config of the HTTP decoder of each connection; override it to tune the decoder further.
   *
   * @return a new config with the limits of this initializer and a max chunk size of
   *     {@linkplain #DEFAULT_MAX_CHUNK_SIZE}.
   */
  protected HttpDecoderConfig decoderConfig() {
    return new HttpDecoderConfig()
        .setMaxInitialLineLength(maxInitialLineLength)
        .setMaxHeaderSize(maxHeaderSize)
        .setMaxChunkSize(DEFAULT_MAX_CHUNK_SIZE);
  }

  /**
   * The time after which a connection that has neither read nor written anything is closed; override it to change
   * or disable the timeout.
   *
   * @return {@linkplain #DEFAULT_IDLE_TIMEOUT}; {@code null}, zero or negative to never close an idle connection.
   */
  protected Duration idleTimeout() {
    return DEFAULT_IDLE_TIMEOUT;
  }

  /**
   * Closes a connection idle for both reads and writes. Not idle for reads only, which a long download is, as the
   * client sends nothing while it receives the response.
   */
  private static class IdleConnectionCloser extends IdleStateHandler {

    IdleConnectionCloser(Duration idleTimeout) {
      super(0, 0, idleTimeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) {
      ctx.close();
    }

  }

}
