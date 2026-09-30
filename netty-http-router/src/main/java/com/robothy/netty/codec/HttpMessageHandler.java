package com.robothy.netty.codec;


import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.netty.router.ExceptionHandler;
import com.robothy.netty.router.Router;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.IOException;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Dispatches {@linkplain RouterHttpRequest}s to the {@linkplain Router} and writes the responses.
 *
 * <p>The request body is released once the handler has returned. A handler that keeps the body, or writes it to the
 * response, must {@linkplain ByteBuf#retain() retain} it.
 */
@Slf4j
public class HttpMessageHandler extends SimpleChannelInboundHandler<RouterHttpRequest> {

  /**
   * Error bodies echo client input, e.g. the request path, so they are plain text rather than HTML to avoid XSS.
   */
  private static final String TEXT_PLAIN_UTF8 = "text/plain; charset=utf-8";

  /**
   * Lower-case names of the request headers that carry credentials, masked in the debug log, which is often pasted
   * into issues or chats.
   */
  private static final Set<String> SENSITIVE_HEADERS =
      Set.of("authorization", "proxy-authorization", "cookie", "x-amz-security-token");

  /**
   * Number of leading characters of a sensitive header kept in the debug log, e.g. the scheme of an
   * {@code Authorization} header.
   */
  private static final int UNMASKED_PREFIX_LENGTH = 8;

  private final Router router;

  public HttpMessageHandler(Router router) {
    this.router = router;
  }

  /**
   * The value of a request header as the debug log shows it: only the first characters of a header carrying
   * credentials.
   *
   * @param name lower-case header name.
   */
  static String headerValueForLog(String name, String value) {
    if (!SENSITIVE_HEADERS.contains(name)) {
      return value;
    }
    // A value no longer than the prefix is masked whole, as it would be shown whole otherwise.
    return value.length() > UNMASKED_PREFIX_LENGTH ? value.substring(0, UNMASKED_PREFIX_LENGTH) + "***" : "***";
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, RouterHttpRequest request) throws Exception {
    if (log.isDebugEnabled()) {
      log.debug("{} {}", request.getMethod(), request.getUri());
      StringBuilder headers = new StringBuilder();
      request.getHeaders().forEach((name, value) -> headers.append("\n").append(name).append(": ")
          .append(headerValueForLog(name, value)));
      log.debug(headers.toString());
    }

    RouterHttpResponse response = new RouterHttpResponse();
    boolean written = false;
    try {
      RouterHttpRequestHandler handler = router.match(request);
      if (null == handler) {
        // A client asking for what isn't there, e.g. a browser for /favicon.ico, isn't a failure of the server.
        log.debug("No handler for {} {}", request.getMethod(), request.getUri());
        response.write("Not found " + request.getPath())
            .status(HttpResponseStatus.NOT_FOUND)
            .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), TEXT_PLAIN_UTF8);
      } else {
        try {
          handler.handle(request, response);
        } catch (Throwable e) {
          ExceptionHandler<Throwable> exceptionHandler = router.findExceptionHandler(e.getClass());
          releaseBody(response);
          response = new RouterHttpResponse();
          try {
            exceptionHandler.handle(e, request, response);
          } catch (Throwable handlerFailure) {
            // Handled by exceptionCaught(), which logs it along with the exception it failed to handle.
            handlerFailure.addSuppressed(e);
            throw handlerFailure;
          }
          logFailure(request, response, e);
        }
      }

      if (null == response.getStatus()) {
        response.status(HttpResponseStatus.OK);
      }

      boolean keepAlive = isKeepAlive(request);
      response.putHeader(HttpHeaderNames.CONNECTION.toString(), keepAlive ? HttpHeaderValues.KEEP_ALIVE : HttpHeaderValues.CLOSE);
      boolean head = HttpMethod.HEAD.equals(request.getMethod());
      if (!hasNoContent(response.getStatus())) {
        if (response.getChunkedBody() == null && !head) {
          correctContentLength(request, response);
        } else {
          // The Content-Length that a handler sets for a HEAD request is the length of the GET response, kept as is.
          long contentLength = response.getChunkedBody() == null
              ? response.getBody().readableBytes() : response.getChunkedBody().length();
          // A chunked body of unknown length is sent with Transfer-Encoding: chunked. A HEAD response without a body
          // says nothing about the length of the resource, which "Content-Length: 0" would claim to be empty.
          boolean unknownHeadLength = head && response.getChunkedBody() == null && contentLength == 0;
          if (contentLength >= 0 && !unknownHeadLength) {
            response.getHeaders().putIfAbsent(HttpHeaderNames.CONTENT_LENGTH.toString(), String.valueOf(contentLength));
          }
        }
      }
      if (head || hasNoContent(response.getStatus())) {
        // No content is sent, so a chunked body that a handler set anyway is closed unread.
        RouterHttpResponse.closeQuietly(response.detachChunkedBody());
      }
      written = true;
      write(ctx, response, keepAlive);

      log.debug("Rendered {} to {} {}", response.getStatus().code(), request.getMethod(), request.getUri());
      if (log.isDebugEnabled()) {
        StringBuilder headers = new StringBuilder();
        response.getAllHeaders().forEach((name, values) -> values.forEach(value -> headers.append("\n")
          .append(name).append(": ").append(headerValueForLog(name, value))));
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
   * Write the response. {@linkplain RouterHttpResponseEncoder} hands the body over to netty, which releases it once written,
   * and the chunked body to the {@linkplain io.netty.handler.stream.ChunkedWriteHandler}, which closes it; if the write
   * fails before that, e.g. the channel is closed, release or close the body here.
   */
  private static void write(ChannelHandlerContext ctx, RouterHttpResponse response, boolean keepAlive) {
    ChannelFuture channelFuture = ctx.writeAndFlush(response).addListener(future -> {
      if (!future.isSuccess()) {
        releaseBody(response);
        // Part of the response may have been sent, e.g. a chunked body failed to be read, so the client can't tell
        // where the next response would start.
        log.debug("Failed to write the response, close the connection.", future.cause());
        ctx.close();
      }
    });
    if (!keepAlive) {
      channelFuture.addListener(ChannelFutureListener.CLOSE);
    }
  }

  /**
   * Log an exception by the response that its handler made of it: a server error, e.g. of the default handler for
   * {@link Throwable} or of a catch-all handler, is an error with its stack trace; any other status, e.g. a 404 that a
   * handler made of a "not found" exception, is part of the normal flow and only logged at debug, with the stack
   * trace at trace.
   */
  private static void logFailure(RouterHttpRequest request, RouterHttpResponse response, Throwable e) {
    // A handler that sets no status answers 200.
    int status = response.getStatus() == null ? HttpResponseStatus.OK.code() : response.getStatus().code();
    if (status >= 500) {
      log.error("Failed to handle {} {}, answered {}.", request.getMethod(), request.getPath(), status, e);
    } else if (log.isTraceEnabled()) {
      log.trace("{} {} answered {}.", request.getMethod(), request.getPath(), status, e);
    } else {
      log.debug("{} {} answered {}: {}", request.getMethod(), request.getPath(), status, e.toString());
    }
  }

  /**
   * Set the {@code Content-Length} of a buffered body to its length. One that a handler set to another length, e.g. by
   * a mistake with a range, would make the client take the rest of the body for the next response, or wait for the
   * missing bytes, so it is replaced with a warning.
   */
  private static void correctContentLength(RouterHttpRequest request, RouterHttpResponse response) {
    String name = HttpHeaderNames.CONTENT_LENGTH.toString();
    String actual = String.valueOf(response.getBody().readableBytes());
    String declared = response.getHeaders().put(name, actual);
    if (declared != null && !declared.trim().equals(actual)) {
      log.warn("The Content-Length {} of the response to {} {} is not the body length {}, sent {} instead.",
          declared, request.getMethod(), request.getPath(), actual, actual);
    }
  }

  /**
   * A 1xx, 204 or 304 response has no content, so its {@code Content-Length} isn't derived from the body: the header
   * must not be sent with 1xx and 204, which netty strips, and with 304 it would have to be the length of the 200
   * response (RFC 9110, section 8.6), not 0.
   */
  static boolean hasNoContent(HttpResponseStatus status) {
    int code = status.code();
    return code < 200 || code == HttpResponseStatus.NO_CONTENT.code() || code == HttpResponseStatus.NOT_MODIFIED.code();
  }

  private static void releaseBody(RouterHttpResponse response) {
    releaseBody(response.getBody());
    // Null once the encoder has taken it over.
    RouterHttpResponse.closeQuietly(response.detachChunkedBody());
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
  static boolean isKeepAlive(RouterHttpRequest request) {
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
    if (cause instanceof IOException || !ctx.channel().isActive()) {
      // The client closed or reset the connection; there is nobody left to send an error response to.
      log.debug("Closing connection {} after: {}", ctx.channel().id(), cause.toString());
      ctx.close();
      return;
    }
    log.error("Caught exception.", cause);
    RouterHttpResponse response = new RouterHttpResponse();
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
    log.debug("Channel {} active.", ctx.channel().id());
    super.channelActive(ctx);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    log.debug("Channel {} inactive.", ctx.channel().id());
    super.channelInactive(ctx);
  }
}
