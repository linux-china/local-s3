package com.robothy.netty.codec;

import com.robothy.netty.utils.RequestTargets;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Aggregates netty HTTP messages into a {@linkplain com.robothy.netty.http.HttpRequest}. It also accepts
 * {@linkplain io.netty.handler.codec.http.FullHttpRequest}s, e.g. from a {@code HttpObjectAggregator}.
 *
 * <p>The body is limited to {@code maxRequestBodySize} bytes. An oversized request is answered with
 * {@code 413 Request Entity Too Large}, and a request that netty failed to decode with {@code 400 Bad Request}; the
 * connection is closed in both cases. When the declared {@code Content-Length} is already too large, the request is
 * rejected before {@code 100 Continue} is sent, so clients that expect it never upload the body.
 */
@Slf4j
public class HttpRequestDecoder extends MessageToMessageDecoder<HttpObject> {

  /**
   * The default max request body size, 64 MiB.
   */
  public static final int DEFAULT_MAX_REQUEST_BODY_SIZE = 64 * 1024 * 1024;

  private final int maxRequestBodySize;

  private com.robothy.netty.http.HttpRequest.HttpRequestBuilder builder;

  private CompositeByteBuf body;

  public HttpRequestDecoder() {
    this(DEFAULT_MAX_REQUEST_BODY_SIZE);
  }

  /**
   * @param maxRequestBodySize max request body size in bytes. The body is held in a single {@linkplain ByteBuf}, so
   *                           it cannot exceed {@code Integer.MAX_VALUE}.
   */
  public HttpRequestDecoder(int maxRequestBodySize) {
    if (maxRequestBodySize <= 0) {
      throw new IllegalArgumentException("maxRequestBodySize must be positive.");
    }
    this.maxRequestBodySize = maxRequestBodySize;
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, HttpObject msg, List<Object> out) throws Exception {
    DecoderResult decoderResult = msg.decoderResult();
    if (decoderResult.isFailure()) {
      log.warn("Failed to decode the HTTP request, close the connection.", decoderResult.cause());
      reject(ctx, HttpResponseStatus.BAD_REQUEST, "Bad Request: " + decoderResult.cause().getMessage());
      return;
    }

    if (msg instanceof HttpRequest) {
      // An unfinished request is replaced, e.g. the previous request was invalid.
      releaseBody();
      HttpRequest httpRequest = (HttpRequest) msg;
      long contentLength = HttpUtil.getContentLength(httpRequest, -1L);
      if (contentLength > maxRequestBodySize) {
        rejectTooLarge(ctx);
        return;
      }

      // Header names are lower case; the values of a repeated header are joined by commas, in the order they were
      // received (RFC 9110, section 5.3).
      HashMap<String, String> headers = new HashMap<>();
      httpRequest.headers().forEach(header -> headers.merge(header.getKey().toLowerCase(Locale.ROOT),
          header.getValue().trim(), (values, value) -> values + "," + value));
      // Parse the URI before allocating the body, a malformed one (e.g. "%zz") must not leave a half-built request.
      // An absolute-form target (e.g. "http://host/a") is converted to its path, "*" and others are not routable.
      RequestTargets.RequestTarget target = RequestTargets.parse(httpRequest.uri());
      if (target == null) {
        log.warn("Unsupported request target '{}', close the connection.", httpRequest.uri());
        reject(ctx, HttpResponseStatus.BAD_REQUEST, "Bad Request: unsupported request target.");
        return;
      }
      String uri = target.originForm();
      // The authority of an absolute-form target takes the place of the Host header (RFC 9112, section 3.2.2).
      if (target.authority() != null) {
        headers.put(HttpHeaderNames.HOST.toString(), target.authority());
      }
      String path;
      Map<String, List<String>> params;
      try {
        // Only '&' separates parameters (RFC 3986, WHATWG URL), netty also splits at ';' by default.
        QueryStringDecoder queryStringDecoder = QueryStringDecoder.builder().semicolonIsNormalChar(true).build(uri);
        path = queryStringDecoder.path();
        params = queryStringDecoder.parameters();
      } catch (IllegalArgumentException e) {
        log.warn("Invalid request URI '{}', close the connection.", httpRequest.uri(), e);
        reject(ctx, HttpResponseStatus.BAD_REQUEST, "Bad Request: invalid URI: " + e.getMessage());
        return;
      }

      // No component limit: consolidating the components of a large body would copy it over and over.
      this.body = Unpooled.compositeBuffer(Integer.MAX_VALUE);
      this.builder = com.robothy.netty.http.HttpRequest.builder()
          .method(httpRequest.method())
          .uri(uri)
          .httpVersion(httpRequest.protocolVersion())
          .headers(headers)
          .body(body)
          .path(path)
          .params(params);

      String expect = httpRequest.headers().getAsString(HttpHeaderNames.EXPECT);
      if (HttpHeaderValues.CONTINUE.contentEqualsIgnoreCase(expect)) {
        ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
      }
    }

    // Not "else if": a FullHttpRequest is both a HttpRequest and a LastHttpContent.
    // The content of a rejected request (body == null) is dropped.
    if (msg instanceof HttpContent && body != null) {
      HttpContent httpContent = (HttpContent) msg;
      ByteBuf content = httpContent.content();
      if ((long) body.readableBytes() + content.readableBytes() > maxRequestBodySize) {
        rejectTooLarge(ctx);
        return;
      }
      ReferenceCountUtil.retain(content);
      body.addComponent(true, content);
      if (msg instanceof LastHttpContent) {
        com.robothy.netty.http.HttpRequest request = builder.build();
        // The body now belongs to the request, HttpMessageHandler releases it.
        this.body = null;
        this.builder = null;
        out.add(request);
      }
    }
  }

  private void rejectTooLarge(ChannelHandlerContext ctx) {
    log.warn("The request body exceeds the max size of {} bytes, close the connection.", maxRequestBodySize);
    reject(ctx, HttpResponseStatus.REQUEST_ENTITY_TOO_LARGE,
        "The request body exceeds the max size of " + maxRequestBodySize + " bytes.");
  }

  /**
   * Drop the unfinished request, answer with {@code status} and close the connection. The content still to come is
   * dropped since {@code body} is {@code null}.
   */
  private void reject(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
    releaseBody();
    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
        Unpooled.copiedBuffer(message, StandardCharsets.UTF_8));
    response.headers()
        .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN)
        .set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
        .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    releaseBody();
    super.channelInactive(ctx);
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    releaseBody();
    super.handlerRemoved(ctx);
  }

  /**
   * Release the body of the unfinished request.
   */
  private void releaseBody() {
    if (body != null) {
      body.release();
      body = null;
      builder = null;
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    if (cause instanceof IOException || !ctx.channel().isActive()) {
      // The client closed or reset the connection, nothing went wrong on this side.
      log.debug("Closing connection {} after: {}", ctx.channel().id(), cause.toString());
    } else {
      log.error("Failed to decode the HTTP request, close the connection.", cause);
    }
    ctx.close();
  }
}
