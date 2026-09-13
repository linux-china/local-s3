package com.robothy.s3.rest.netty;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.router.Router;
import com.robothy.s3.rest.utils.ErrorResponses;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches {@linkplain HttpRequest}s to the {@linkplain Router} and writes the responses.
 *
 * <p>Handlers receive a {@linkplain StreamingHttpResponse}, so they can stream large bodies.
 * The request body is released once the handler has returned.
 *
 * <p>The handler sits on the channel's event loop and runs the router on {@code executor}, a pool shared by all
 * connections, so that a slow request only holds up the requests of its own connection. The requests of a
 * connection are handled one at a time, in the order they were received, and each response is written on the
 * event loop before the next request is handled. While a request is in flight the channel stops reading, so that
 * the bodies of further requests are not received, and pile up, before the request has been answered.
 */
public class LocalS3HttpMessageHandler extends ChannelInboundHandlerAdapter {

  private static final Logger log = LoggerFactory.getLogger(LocalS3HttpMessageHandler.class);

  private final Router router;

  private final Executor executor;

  /**
   * Requests received, e.g. pipelined, while another request of the connection is in flight. Only accessed on the
   * event loop.
   */
  private final Queue<HttpRequest> pendingRequests = new ArrayDeque<>();

  /**
   * Whether a request is being handled, or its response written. Only accessed on the event loop.
   */
  private boolean inFlight;

  /**
   * Create a handler that handles requests on the event loop.
   *
   * @param router routes requests to handlers.
   */
  public LocalS3HttpMessageHandler(Router router) {
    this(router, Runnable::run);
  }

  /**
   * Create a handler.
   *
   * @param router   routes requests to handlers.
   * @param executor runs the handlers of the requests, shared by all connections.
   */
  public LocalS3HttpMessageHandler(Router router, Executor executor) {
    this.router = router;
    this.executor = Objects.requireNonNull(executor);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    if (!(msg instanceof HttpRequest request)) {
      ctx.fireChannelRead(msg);
      return;
    }
    // Received bytes may still hold pipelined requests, but no more is read until the connection is idle again.
    ctx.channel().config().setAutoRead(false);
    pendingRequests.add(request);
    handleNext(ctx);
  }

  /**
   * Hand the next pending request to the executor, or resume reading once there is none.
   */
  private void handleNext(ChannelHandlerContext ctx) {
    if (inFlight) {
      return;
    }
    HttpRequest request = pendingRequests.poll();
    if (request == null) {
      if (ctx.channel().isActive()) {
        ctx.channel().config().setAutoRead(true);
      }
      return;
    }

    inFlight = true;
    try {
      executor.execute(() -> handleOnExecutor(ctx, request));
    } catch (RejectedExecutionException e) {
      // The server is shutting down.
      log.debug("Closing connection {}: the request executor rejected {} {}.", ctx.channel().id(),
          request.getMethod(), request.getUri());
      releaseBody(request);
      releasePendingRequests();
      ctx.close();
    }
  }

  private void handleOnExecutor(ChannelHandlerContext ctx, HttpRequest request) {
    StreamingHttpResponse response = null;
    Throwable failure = null;
    try {
      response = handle(request);
      boolean keepAlive = isKeepAlive(request);
      response.putHeader(HttpHeaderNames.CONNECTION.toString(), keepAlive ? HttpHeaderValues.KEEP_ALIVE : HttpHeaderValues.CLOSE);
      if (!response.isStreaming()) {
        response.getHeaders().putIfAbsent(HttpHeaderNames.CONTENT_LENGTH.toString(),
            String.valueOf(response.getBody().readableBytes()));
      }
    } catch (Throwable e) {
      failure = e;
    } finally {
      releaseBody(request);
    }

    StreamingHttpResponse result = response;
    Throwable cause = failure;
    Runnable complete = () -> complete(ctx, request, result, cause);
    if (ctx.executor().inEventLoop()) {
      complete.run();
      return;
    }
    try {
      ctx.executor().execute(complete);
    } catch (RejectedExecutionException e) {
      // The event loop has terminated, and the connection with it.
      if (result != null) {
        result.discard();
      }
    }
  }

  /**
   * Write the response of a handled request on the event loop, and go on with the next request once it is written.
   */
  private void complete(ChannelHandlerContext ctx, HttpRequest request, StreamingHttpResponse response,
                        Throwable failure) {
    if (failure != null) {
      if (response != null) {
        response.discard();
      }
      inFlight = false;
      releasePendingRequests();
      exceptionCaught(ctx, failure);
      return;
    }

    ChannelFuture future = ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    if (!isKeepAlive(request)) {
      // Nothing after this response is handled.
      releasePendingRequests();
      future.addListener(ChannelFutureListener.CLOSE);
      return;
    }
    future.addListener(written -> {
      inFlight = false;
      if (written.isSuccess()) {
        handleNext(ctx);
      } else {
        releasePendingRequests();
      }
    });
  }

  private static void releaseBody(HttpRequest request) {
    ByteBuf body = request.getBody();
    if (body != null && body.refCnt() > 0) {
      body.release();
    }
  }

  private void releasePendingRequests() {
    HttpRequest request;
    while ((request = pendingRequests.poll()) != null) {
      releaseBody(request);
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    releasePendingRequests();
    super.channelInactive(ctx);
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) {
    releasePendingRequests();
  }

  /**
   * Same semantics as {@linkplain io.netty.handler.codec.http.HttpUtil#isKeepAlive}: {@code Connection: close}
   * always closes the connection, otherwise HTTP/1.1 keeps it alive by default and HTTP/1.0 only
   * with {@code Connection: keep-alive}.
   */
  static boolean isKeepAlive(HttpRequest request) {
    boolean keepAlive = request.getHttpVersion().isKeepAliveDefault();
    String connection = request.header(HttpHeaderNames.CONNECTION.toString()).orElse(null);
    if (connection != null) {
      // The Connection header is a comma-separated list of tokens, e.g. "keep-alive, Upgrade".
      for (String token : connection.split(",")) {
        String value = token.trim();
        if (HttpHeaderValues.CLOSE.contentEqualsIgnoreCase(value)) {
          return false;
        }
        if (HttpHeaderValues.KEEP_ALIVE.contentEqualsIgnoreCase(value)) {
          keepAlive = true;
        }
      }
    }
    return keepAlive;
  }

  private StreamingHttpResponse handle(HttpRequest request) {
    StreamingHttpResponse response = new StreamingHttpResponse();
    HttpRequestHandler handler = router.match(request);
    if (handler == null) {
      log.warn("No handler for {} {}", request.getMethod(), request.getUri());
      ErrorResponses.notImplemented(request, response);
    } else {
      try {
        handler.handle(request, response);
      } catch (Exception e) {
        StreamingHttpResponse failed = response;
        failed.discard();
        response = new StreamingHttpResponse();
        copyCorsHeaders(failed, response);
        try {
          router.findExceptionHandler(e.getClass()).handle(e, request, response);
        } catch (RuntimeException handlerFailure) {
          // exceptionCaught() logs it, together with the exception it failed to handle.
          handlerFailure.addSuppressed(e);
          throw handlerFailure;
        }
        logFailure(request, response, e);
      }
    }

    if (response.getStatus() == null) {
      response.status(HttpResponseStatus.OK);
    }
    return response;
  }

  /**
   * Log a request that failed with an exception, by the status its exception handler answered. An error of the
   * client, e.g. {@code NoSuchKey} of a {@code HEAD} request that checks whether an object exists, is part of normal
   * operation and only logged at {@code DEBUG}, with the stack trace at {@code TRACE}. So is {@code NotImplemented},
   * at {@code WARN} like a request that no route matches. Only a server error, e.g. an unexpected exception that
   * the fallback handler answers with {@code InternalError}, is logged at {@code ERROR} with its stack trace.
   */
  static void logFailure(HttpRequest request, StreamingHttpResponse response, Exception e) {
    int status = response.getStatus() == null ? HttpResponseStatus.OK.code() : response.getStatus().code();
    if (status == HttpResponseStatus.NOT_IMPLEMENTED.code()) {
      log.warn("{} {} is not implemented: {}", request.getMethod(), request.getPath(), e.toString());
    } else if (status >= 500) {
      log.error("Failed to handle " + request.getMethod() + " " + request.getPath(), e);
    } else if (log.isTraceEnabled()) {
      log.trace("{} {} answered {}.", request.getMethod(), request.getPath(), status, e);
    } else {
      log.debug("{} {} answered {}: {}", request.getMethod(), request.getPath(), status, e.toString());
    }
  }

  /**
   * Keep the CORS headers of a failed response in the error response, so that browsers let the page read the error.
   */
  private static void copyCorsHeaders(StreamingHttpResponse from, StreamingHttpResponse to) {
    from.getHeaders().forEach((name, value) -> {
      String lowerCaseName = name.toLowerCase(Locale.ROOT);
      if (lowerCaseName.startsWith("access-control-") || "vary".equals(lowerCaseName)) {
        to.putHeader(name, value);
      }
    });
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
    releasePendingRequests();
    if (inFlight) {
      // The response of the request in flight is still to come; an error response now would be taken for it.
      ctx.close();
      return;
    }
    // The cause is logged above, and not revealed to the client.
    StreamingHttpResponse response = new StreamingHttpResponse();
    ErrorResponses.internalError(null, response);
    response.putHeader(HttpHeaderNames.CONNECTION.toString(), HttpHeaderValues.CLOSE)
        .putHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), response.getBody().readableBytes());
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

}
