package com.robothy.netty.codec;


import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.netty.router.ExceptionHandler;
import com.robothy.netty.router.Router;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.extern.slf4j.Slf4j;

/**
 * Dispatches {@linkplain HttpRequest}s to the {@linkplain Router} and writes the responses.
 *
 * <p>The request body is released once the handler has returned. A handler that keeps the body, or writes it to the
 * response, must {@linkplain ByteBuf#retain() retain} it.
 */
@Slf4j
public class HttpMessageHandler extends SimpleChannelInboundHandler<HttpRequest> {

  /**
   * Error bodies echo client input, e.g. the request path, so they are plain text rather than HTML to avoid XSS.
   */
  private static final String TEXT_PLAIN_UTF8 = "text/plain; charset=utf-8";

  private final Router router;

  public HttpMessageHandler(Router router) {
    this.router = router;
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, HttpRequest request) throws Exception {
    if (log.isDebugEnabled()) {
      log.debug("{} {}", request.getMethod(), request.getUri());
      StringBuilder headers = new StringBuilder();
      request.getHeaders().forEach((name, value) -> headers.append("\n").append(name).append(": ").append(value));
      log.debug(headers.toString());
    }

    HttpResponse response = new HttpResponse();
    boolean written = false;
    try {
      HttpRequestHandler handler = router.match(request);
      if (null == handler) {
        log.warn("No handler for {} {}", request.getMethod(), request.getUri());
        response.write("Not found " + request.getPath())
            .status(HttpResponseStatus.NOT_FOUND)
            .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), TEXT_PLAIN_UTF8);
      } else {
        try {
          handler.handle(request, response);
        } catch (Throwable e) {
          log.error("Failed to handle " + request.getMethod() + " " + request.getPath(), e);
          ExceptionHandler<Throwable> exceptionHandler = router.findExceptionHandler(e.getClass());
          releaseBody(response);
          response = new HttpResponse();
          // Exceptions from exceptionHandler will be handled by exceptionCaught().
          exceptionHandler.handle(e, request, response);
        }
      }

      if (null == response.getStatus()) {
        response.status(HttpResponseStatus.OK);
      }

      boolean keepAlive = isKeepAlive(request);
      response.putHeader(HttpHeaderNames.CONNECTION.toString(), keepAlive ? HttpHeaderValues.KEEP_ALIVE : HttpHeaderValues.CLOSE);
      response.getHeaders().putIfAbsent(HttpHeaderNames.CONTENT_LENGTH.toString(),
          String.valueOf(response.getBody().readableBytes()));
      written = true;
      write(ctx, response, keepAlive);

      log.info("Rendered {} to {} {}", response.getStatus().code(), request.getMethod(), request.getUri());
      if (log.isDebugEnabled()) {
        StringBuilder headers = new StringBuilder();
        response.getHeaders().forEach((name, value) -> headers.append("\n").append(name).append(": ").append(value));
        log.debug(headers.toString());
      }
    } finally {
      if (!written) {
        releaseBody(response);
      }
      // The request body is released once the handler has returned, retain it to keep it longer.
      releaseBody(request.getBody());
    }
  }

  /**
   * Write the response. {@linkplain HttpResponseEncoder} hands the body over to netty, which releases it once written;
   * if the write fails before that, e.g. the channel is closed, release the body here.
   */
  private static void write(ChannelHandlerContext ctx, HttpResponse response, boolean keepAlive) {
    ChannelFuture channelFuture = ctx.writeAndFlush(response).addListener(future -> {
      if (!future.isSuccess()) {
        releaseBody(response);
      }
    });
    if (!keepAlive) {
      channelFuture.addListener(ChannelFutureListener.CLOSE);
    }
  }

  private static void releaseBody(HttpResponse response) {
    releaseBody(response.getBody());
  }

  private static void releaseBody(ByteBuf body) {
    if (body != null && body.refCnt() > 0) {
      body.release();
    }
  }

  /**
   * Same as {@linkplain io.netty.handler.codec.http.HttpUtil#isKeepAlive}: HTTP/1.1 keeps the connection alive unless
   * the request has {@code Connection: close}; HTTP/1.0 closes the connection unless the request has
   * {@code Connection: keep-alive}. The header value may be a comma-separated list and is case-insensitive.
   */
  static boolean isKeepAlive(HttpRequest request) {
    String connection = request.header(HttpHeaderNames.CONNECTION.toString()).orElse("");
    if (request.getHttpVersion().isKeepAliveDefault()) {
      return !containsToken(connection, HttpHeaderValues.CLOSE.toString());
    }
    return containsToken(connection, HttpHeaderValues.KEEP_ALIVE.toString());
  }

  private static boolean containsToken(String headerValue, String token) {
    for (String value : headerValue.split(",")) {
      if (token.equalsIgnoreCase(value.trim())) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    log.error("Caught exception.", cause);
    HttpResponse response = new HttpResponse();
    response.status(HttpResponseStatus.INTERNAL_SERVER_ERROR)
        .write("Internal Server Error.")
        .write(cause.getMessage() == null ? null : " " + cause.getMessage())
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), TEXT_PLAIN_UTF8)
        .putHeader(HttpHeaderNames.CONNECTION.toString(), HttpHeaderValues.CLOSE)
        .putHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), response.getBody().readableBytes());
    write(ctx, response, false);
  }

  @Override
  public void channelActive(ChannelHandlerContext ctx) throws Exception {
    log.info("Channel " + ctx.channel().id() + " active.");
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    Channel ch = ctx.channel();
    log.info("Channel " + ch.id() + " inactive.");
  }
}
