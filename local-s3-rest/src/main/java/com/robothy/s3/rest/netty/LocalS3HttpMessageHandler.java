package com.robothy.s3.rest.netty;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.router.Router;
import com.robothy.s3.core.exception.TotalSizeExceedException;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.utils.ErrorResponses;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.ssl.NotSslRecordException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches {@linkplain RouterHttpRequest}s to the {@linkplain Router} on {@code executor} and writes the responses;
 * handlers get a {@linkplain StreamingRouterHttpResponse}, and the body is released once the handler returns.
 *
 * <p>The requests of a connection are handled one at a time, in order, and the channel stops reading while one is in
 * flight, so further bodies don't pile up; a slow request only holds up its own connection. A request is counted in
 * {@linkplain InFlightRequests} until its response is written, which a shutdown waits for, and then handed to the
 * {@linkplain RequestRecorder} with the operation the router named ({@linkplain OperationHandler}).
 */
public class LocalS3HttpMessageHandler extends ChannelInboundHandlerAdapter {

  private static final Logger log = LoggerFactory.getLogger(LocalS3HttpMessageHandler.class);

  private final Router router;

  private final Executor executor;

  private final InFlightRequests inFlightRequests;

  private final RequestRecorder requestRecorder;

  /**
   * Requests received, e.g. pipelined, while another request of the connection is in flight. Only accessed on the
   * event loop.
   */
  private final Queue<ReceivedRequest> pendingRequests = new ArrayDeque<>();

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
    this(router, executor, InFlightRequests.NONE);
  }

  /**
   * Create a handler.
   *
   * @param router           routes requests to handlers.
   * @param executor         runs the handlers of the requests, shared by all connections.
   * @param inFlightRequests counts the requests in flight, shared by all connections.
   */
  public LocalS3HttpMessageHandler(Router router, Executor executor, InFlightRequests inFlightRequests) {
    this(router, executor, inFlightRequests, RequestRecorder.NONE);
  }

  /**
   * Create a handler.
   *
   * @param router           routes requests to handlers.
   * @param executor         runs the handlers of the requests, shared by all connections.
   * @param inFlightRequests counts the requests in flight, shared by all connections.
   * @param requestRecorder  receives the requests once their responses are written, shared by all connections.
   */
  public LocalS3HttpMessageHandler(Router router, Executor executor, InFlightRequests inFlightRequests,
                                   RequestRecorder requestRecorder) {
    this.router = router;
    this.executor = Objects.requireNonNull(executor);
    this.inFlightRequests = Objects.requireNonNull(inFlightRequests);
    this.requestRecorder = Objects.requireNonNull(requestRecorder);
  }

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object msg) {
    ReceivedRequest received;
    if (msg instanceof ReceivedRequest decoded) {
      received = decoded;
    } else if (msg instanceof RouterHttpRequest request) {
      // Nothing is known about a request that another decoder, or a test, hands on.
      received = new ReceivedRequest(request, null, null);
    } else {
      ctx.fireChannelRead(msg);
      return;
    }
    // Received bytes may still hold pipelined requests, but no more is read until the connection is idle again.
    ReadSuspensions.suspend(ctx.channel(), this);
    pendingRequests.add(received);
    handleNext(ctx);
  }

  /**
   * Hand the next pending request to the executor, or resume reading once there is none.
   */
  private void handleNext(ChannelHandlerContext ctx) {
    if (inFlight) {
      return;
    }
    ReceivedRequest received = pendingRequests.poll();
    if (received == null) {
      ReadSuspensions.resume(ctx.channel(), this);
      return;
    }
    RouterHttpRequest request = received.request();

    inFlight = true;
    Runnable end = endOnce();
    long startNanos = System.nanoTime();
    try {
      executor.execute(() -> handleOnExecutor(ctx, received, end, startNanos));
    } catch (RejectedExecutionException e) {
      // The server is shutting down.
      log.debug("Closing connection {}: the request executor rejected {} {}.", ctx.channel().id(),
          request.getMethod(), request.getUri());
      end.run();
      releaseBody(request);
      releasePendingRequests();
      ctx.close();
    }
  }

  /**
   * Count a request in flight, and return the action that ends it, which may be run more than once but ends it once.
   */
  private Runnable endOnce() {
    inFlightRequests.begin();
    AtomicBoolean ended = new AtomicBoolean();
    return () -> {
      if (ended.compareAndSet(false, true)) {
        inFlightRequests.end();
      }
    };
  }

  /**
   * The operation that a request was routed to, and its response.
   */
  private record Handled(String operation, StreamingRouterHttpResponse response) {
  }

  private void handleOnExecutor(ChannelHandlerContext ctx, ReceivedRequest received, Runnable end, long startNanos) {
    RouterHttpRequest request = received.request();
    StreamingRouterHttpResponse response = null;
    Throwable failure = null;
    String operation = OperationHandler.UNKNOWN_OPERATION;
    // The operation that the router named, kept if the handler then fails.
    String[] routed = {operation};
    try {
      // Bound to this thread while the request is routed and handled, where the router and the controllers read it.
      Handled handled = received.handle(() -> {
        RouterHttpRequestHandler handler = router.match(request);
        routed[0] = OperationHandler.operationOf(handler);
        return new Handled(routed[0], handle(request, handler));
      });
      operation = handled.operation();
      response = handled.response();
      boolean keepAlive = isKeepAlive(request);
      response.putHeader(HttpHeaderNames.CONNECTION.toString(), keepAlive ? HttpHeaderValues.KEEP_ALIVE : HttpHeaderValues.CLOSE);
      if (!response.isStreaming() && !hasNoContent(response.getStatus())) {
        response.getHeaders().putIfAbsent(HttpHeaderNames.CONTENT_LENGTH.toString(),
            String.valueOf(response.getBody().readableBytes()));
      }
    } catch (Throwable e) {
      failure = e;
      operation = routed[0];
    } finally {
      releaseBody(request);
    }

    StreamingRouterHttpResponse result = response;
    Throwable cause = failure;
    String matchedOperation = operation;
    Runnable complete = () -> complete(ctx, request, result, cause, end, matchedOperation, startNanos);
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
      end.run();
    }
  }

  /**
   * Write the response of a handled request on the event loop, and go on with the next request once it is written.
   */
  private void complete(ChannelHandlerContext ctx, RouterHttpRequest request, StreamingRouterHttpResponse response,
                        Throwable failure, Runnable end, String operation, long startNanos) {
    if (failure != null) {
      if (response != null) {
        response.discard();
      }
      end.run();
      // Answered with an internal error, or not at all if the connection is closed.
      record(request, operation, HttpResponseStatus.INTERNAL_SERVER_ERROR.code(), null, startNanos);
      inFlight = false;
      releasePendingRequests();
      exceptionCaught(ctx, failure);
      return;
    }

    int status = response.getStatus().code();
    String requestId = response.getHeaders().get(AmzHeaderNames.X_AMZ_REQUEST_ID);
    ChannelFuture future = ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
    // The request is in flight until its response is written, or fails to be written.
    future.addListener(written -> {
      end.run();
      record(request, operation, status, requestId, startNanos);
    });
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

  private void record(RouterHttpRequest request, String operation, int status, String requestId, long startNanos) {
    try {
      requestRecorder.record(request, operation, status, requestId, System.nanoTime() - startNanos);
    } catch (RuntimeException e) {
      log.warn("Failed to record {} {}.", request.getMethod(), request.getUri(), e);
    }
  }

  /**
   * A 1xx, 204 or 304 response has no content, so its {@code Content-Length} isn't derived from the body: the header
   * must not be sent with 1xx and 204, which netty strips, and with the 304 of a conditional GetObject it would have
   * to be the size of the object (RFC 9110, section 8.6), not 0.
   */
  static boolean hasNoContent(HttpResponseStatus status) {
    int code = status.code();
    return code < 200 || code == HttpResponseStatus.NO_CONTENT.code() || code == HttpResponseStatus.NOT_MODIFIED.code();
  }

  private static void releaseBody(RouterHttpRequest request) {
    ByteBuf body = request.getBody();
    if (body != null && body.refCnt() > 0) {
      body.release();
    }
  }

  private void releasePendingRequests() {
    ReceivedRequest received;
    while ((received = pendingRequests.poll()) != null) {
      releaseBody(received.request());
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
  static boolean isKeepAlive(RouterHttpRequest request) {
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

  private StreamingRouterHttpResponse handle(RouterHttpRequest request, RouterHttpRequestHandler handler) {
    StreamingRouterHttpResponse response = new StreamingRouterHttpResponse();
    if (handler == null) {
      log.warn("No handler for {} {}", request.getMethod(), request.getUri());
      ErrorResponses.notImplemented(request, response);
    } else {
      try {
        handler.handle(request, response);
      } catch (Exception e) {
        StreamingRouterHttpResponse failed = response;
        failed.discard();
        response = new StreamingRouterHttpResponse();
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
  static void logFailure(RouterHttpRequest request, StreamingRouterHttpResponse response, Exception e) {
    int status = response.getStatus() == null ? HttpResponseStatus.OK.code() : response.getStatus().code();
    if (e instanceof TotalSizeExceedException) {
      // A limit that the configuration sets, not a failure of the service; the message tells how to raise it.
      log.warn("{} {} answered {}: {}", request.getMethod(), request.getPath(), status, e.getMessage());
    } else if (status == HttpResponseStatus.NOT_IMPLEMENTED.code()) {
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
  private static void copyCorsHeaders(StreamingRouterHttpResponse from, StreamingRouterHttpResponse to) {
    from.getAllHeaders().forEach((name, values) -> {
      String lowerCaseName = name.toLowerCase(Locale.ROOT);
      if (lowerCaseName.startsWith("access-control-") || "vary".equals(lowerCaseName)) {
        to.getHeaders().remove(name);
        values.forEach(value -> to.addHeader(name, value));
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
    if (isTlsFailure(cause)) {
      // A TLS handshake failed, e.g. a plain HTTP request to an HTTPS service or a client that doesn't trust the
      // certificate; the SslHandler already answered with an alert, if anything.
      log.debug("Closing connection {} after a TLS failure: {}", ctx.channel().id(), cause.toString());
      ctx.close();
      return;
    }

    if (!ctx.channel().isActive()) {
      // The connection is already closed; the failure can't be answered, and is most likely a consequence of it.
      log.debug("Caught exception on closed connection {}: {}", ctx.channel().id(), cause.toString());
      ctx.close();
      return;
    }

    log.error("Caught exception.", cause);
    releasePendingRequests();
    if (inFlight) {
      // The response of the request in flight is still to come; an error response now would be taken for it.
      ctx.close();
      return;
    }
    // The cause is logged above, and not revealed to the client.
    StreamingRouterHttpResponse response = new StreamingRouterHttpResponse();
    try {
      ErrorResponses.internalError(null, response);
    } catch (RuntimeException | Error e) {
      // E.g. the error document can't be serialized, which a native executable without the metadata of the model
      // fails at. Closing the connection tells the client at once, rather than leaving it to wait for a response that
      // never comes until its read timeout.
      log.error("Failed to answer the failure with an error response; closing connection {}.", ctx.channel().id(), e);
      response.discard();
      ctx.close();
      return;
    }
    response.putHeader(HttpHeaderNames.CONNECTION.toString(), HttpHeaderValues.CLOSE)
        .putHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), response.getBody().readableBytes());
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

  private static boolean isTlsFailure(Throwable cause) {
    return cause instanceof SSLException || cause instanceof NotSslRecordException
        || (cause instanceof DecoderException && cause.getCause() instanceof SSLException);
  }

}
