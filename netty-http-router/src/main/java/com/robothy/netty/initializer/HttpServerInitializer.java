package com.robothy.netty.initializer;

import com.robothy.netty.codec.HttpMessageHandler;
import com.robothy.netty.router.Router;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.concurrent.EventExecutorGroup;

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

  private final EventExecutorGroup executorGroup;

  private final Router router;

  private final int maxRequestBodySize;

  private final int maxInitialLineLength;

  private final int maxHeaderSize;

  public HttpServerInitializer(EventExecutorGroup executorGroup, Router router) {
    this(executorGroup, router, com.robothy.netty.codec.HttpRequestDecoder.DEFAULT_MAX_REQUEST_BODY_SIZE);
  }

  /**
   * @param maxRequestBodySize max request body size in bytes, a larger request is answered with
   *                           {@code 413 Request Entity Too Large}.
   */
  public HttpServerInitializer(EventExecutorGroup executorGroup, Router router, int maxRequestBodySize) {
    this(executorGroup, router, maxRequestBodySize, DEFAULT_MAX_INITIAL_LINE_LENGTH, DEFAULT_MAX_HEADER_SIZE);
  }

  /**
   * @param maxRequestBodySize   max request body size in bytes, a larger request is answered with
   *                             {@code 413 Request Entity Too Large}.
   * @param maxInitialLineLength max length in bytes of the request line, positive.
   * @param maxHeaderSize        max size in bytes of the header section of a request, positive.
   */
  public HttpServerInitializer(EventExecutorGroup executorGroup, Router router, int maxRequestBodySize,
                               int maxInitialLineLength, int maxHeaderSize) {
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
    ch.config().setConnectTimeoutMillis(10000);
    ch.config().setAutoClose(true);
    // Unlike a separate decoder and encoder, the codec pairs each response with its request, so the response to a HEAD
    // request keeps its Content-Length but not its body, which would be taken for the start of the next response.
    pipeline.addLast("http-server-codec", new HttpServerCodec(new HttpDecoderConfig()
        .setMaxInitialLineLength(maxInitialLineLength)
        .setMaxHeaderSize(maxHeaderSize)));
    pipeline.addLast(this.executorGroup, "router-http-request-decoder", new com.robothy.netty.codec.HttpRequestDecoder(maxRequestBodySize));
    pipeline.addLast(this.executorGroup, "router-http-response-encoder", new com.robothy.netty.codec.HttpResponseEncoder());
    pipeline.addLast(this.executorGroup, "router-http-message-handler", new HttpMessageHandler(router));
  }

}
