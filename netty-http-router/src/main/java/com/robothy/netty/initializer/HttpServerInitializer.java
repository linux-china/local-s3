package com.robothy.netty.initializer;

import com.robothy.netty.codec.HttpMessageHandler;
import com.robothy.netty.router.Router;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.concurrent.EventExecutorGroup;

public class HttpServerInitializer extends ChannelInitializer<SocketChannel> {

  private final EventExecutorGroup executorGroup;

  private final Router router;

  private final int maxRequestBodySize;

  public HttpServerInitializer(EventExecutorGroup executorGroup, Router router) {
    this(executorGroup, router, com.robothy.netty.codec.HttpRequestDecoder.DEFAULT_MAX_REQUEST_BODY_SIZE);
  }

  /**
   * @param maxRequestBodySize max request body size in bytes, a larger request is answered with
   *                           {@code 413 Request Entity Too Large}.
   */
  public HttpServerInitializer(EventExecutorGroup executorGroup, Router router, int maxRequestBodySize) {
    this.executorGroup = executorGroup;
    this.router = router;
    this.maxRequestBodySize = maxRequestBodySize;
  }

  @Override
  protected void initChannel(SocketChannel ch) throws Exception {
    ChannelPipeline pipeline = ch.pipeline();
    ch.config().setConnectTimeoutMillis(10000);
    ch.config().setAutoClose(true);
    // Unlike a separate decoder and encoder, the codec pairs each response with its request, so the response to a HEAD
    // request keeps its Content-Length but not its body, which would be taken for the start of the next response.
    pipeline.addLast("http-server-codec", new HttpServerCodec());
    pipeline.addLast(this.executorGroup, "router-http-request-decoder", new com.robothy.netty.codec.HttpRequestDecoder(maxRequestBodySize));
    pipeline.addLast(this.executorGroup, "router-http-response-encoder", new com.robothy.netty.codec.HttpResponseEncoder());
    pipeline.addLast(this.executorGroup, "router-http-message-handler", new HttpMessageHandler(router));
  }

}
