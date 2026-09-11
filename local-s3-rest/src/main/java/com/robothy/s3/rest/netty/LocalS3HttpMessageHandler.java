package com.robothy.s3.rest.netty;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.router.Router;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches {@linkplain HttpRequest}s to the {@linkplain Router} and writes the responses.
 *
 * <p>Handlers receive a {@linkplain StreamingHttpResponse}, so they can stream large bodies.
 * The request body is released once the handler has returned.
 */
public class LocalS3HttpMessageHandler extends SimpleChannelInboundHandler<HttpRequest> {

  private static final Logger log = LoggerFactory.getLogger(LocalS3HttpMessageHandler.class);

  private final Router router;

  public LocalS3HttpMessageHandler(Router router) {
    this.router = router;
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, HttpRequest request) {
    try {
      StreamingHttpResponse response = handle(request);
      boolean keepAlive = request.getHttpVersion().isKeepAliveDefault()
          || HttpHeaderValues.KEEP_ALIVE.contentEqualsIgnoreCase(request.header(HttpHeaderNames.CONNECTION.toString()).orElse(null));
      response.putHeader(HttpHeaderNames.CONNECTION.toString(), keepAlive ? HttpHeaderValues.KEEP_ALIVE : HttpHeaderValues.CLOSE);
      if (!response.isStreaming()) {
        response.getHeaders().putIfAbsent(HttpHeaderNames.CONTENT_LENGTH.toString(),
            String.valueOf(response.getBody().readableBytes()));
      }

      ChannelFuture future = ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
      if (!keepAlive) {
        future.addListener(ChannelFutureListener.CLOSE);
      }
    } finally {
      ByteBuf body = request.getBody();
      if (body != null && body.refCnt() > 0) {
        body.release();
      }
    }
  }

  private StreamingHttpResponse handle(HttpRequest request) {
    StreamingHttpResponse response = new StreamingHttpResponse();
    HttpRequestHandler handler = router.match(request);
    if (handler == null) {
      log.warn("No handler for {} {}", request.getMethod(), request.getUri());
      response.write("Not found" + request.getPath())
          .status(HttpResponseStatus.NOT_FOUND)
          .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.TEXT_HTML);
    } else {
      try {
        handler.handle(request, response);
      } catch (Exception e) {
        log.error("Failed to handle " + request.getMethod() + " " + request.getPath(), e);
        response.discard();
        response = new StreamingHttpResponse();
        router.findExceptionHandler(e.getClass()).handle(e, request, response);
      }
    }

    if (response.getStatus() == null) {
      response.status(HttpResponseStatus.OK);
    }
    return response;
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    if (cause instanceof IOException) {
      // The client closed or reset the connection; there is nobody left to send an error response to.
      log.debug("Closing connection {} after an I/O error: {}", ctx.channel().id(), cause.toString());
      ctx.close();
      return;
    }

    log.error("Caught exception.", cause);
    if (!ctx.channel().isActive()) {
      return;
    }
    StreamingHttpResponse response = new StreamingHttpResponse();
    response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR).write("<h1>Internal Server Error.</h1>");
    if (cause.getMessage() != null) {
      response.write(cause.getMessage());
    }
    response.putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.TEXT_HTML)
        .putHeader(HttpHeaderNames.CONNECTION.toString(), HttpHeaderValues.CLOSE)
        .putHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), response.getBody().readableBytes());
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

}
