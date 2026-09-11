package com.robothy.s3.rest.netty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.util.IdUtils;
import com.robothy.s3.datatypes.response.S3Error;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aggregates Netty HTTP messages into an {@linkplain HttpRequest} for the router.
 *
 * <p>The body is limited to {@code maxRequestBodySize} bytes. An oversized request is answered with
 * an S3 {@code EntityTooLarge} error and the connection is closed. When the declared
 * {@code Content-Length} is already too large, the request is rejected before {@code 100 Continue}
 * is sent, so clients that expect it never upload the body.
 */
public class LocalS3HttpRequestDecoder extends MessageToMessageDecoder<HttpObject> {

  private final long maxRequestBodySize;

  private final XmlMapper xmlMapper;

  private HttpRequest.HttpRequestBuilder builder;

  private CompositeByteBuf body;

  private long receivedBytes;

  private boolean rejected;

  /**
   * Create a decoder.
   *
   * @param maxRequestBodySize max request body size in bytes.
   * @param xmlMapper used to render the error of oversized requests.
   */
  public LocalS3HttpRequestDecoder(long maxRequestBodySize, XmlMapper xmlMapper) {
    if (maxRequestBodySize <= 0) {
      throw new IllegalArgumentException("maxRequestBodySize must be positive.");
    }
    this.maxRequestBodySize = maxRequestBodySize;
    this.xmlMapper = xmlMapper;
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, HttpObject msg, List<Object> out) {
    if (rejected) {
      // The connection is closing after an oversized request; drop whatever is still arriving.
      return;
    }

    if (msg instanceof io.netty.handler.codec.http.HttpRequest request) {
      releaseBody();
      if (HttpUtil.getContentLength(request, -1L) > maxRequestBodySize) {
        reject(ctx);
        return;
      }
      startRequest(ctx, request);
    }

    if (msg instanceof HttpContent content && builder != null) {
      receivedBytes += content.content().readableBytes();
      if (receivedBytes > maxRequestBodySize) {
        reject(ctx);
        return;
      }

      body.addComponent(true, content.content().retain());
      if (msg instanceof LastHttpContent) {
        HttpRequest request = builder.build();
        // The body now belongs to the request; LocalS3HttpMessageHandler releases it.
        builder = null;
        body = null;
        out.add(request);
      }
    }
  }

  private void startRequest(ChannelHandlerContext ctx, io.netty.handler.codec.http.HttpRequest request) {
    Map<CharSequence, String> headers = new HashMap<>();
    request.headers().forEach(header -> headers.put(header.getKey().toLowerCase(Locale.ROOT), header.getValue()));
    QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.uri());

    // No component limit: consolidating the components of a large body would copy it over and over.
    body = Unpooled.compositeBuffer(Integer.MAX_VALUE);
    receivedBytes = 0;
    builder = HttpRequest.builder()
        .method(request.method())
        .uri(request.uri())
        .httpVersion(request.protocolVersion())
        .headers(headers)
        .body(body)
        .path(queryStringDecoder.path())
        .params(new HashMap<>(queryStringDecoder.parameters()));

    if (HttpHeaderValues.CONTINUE.contentEqualsIgnoreCase(request.headers().get(HttpHeaderNames.EXPECT))) {
      ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
    }
  }

  private void reject(ChannelHandlerContext ctx) {
    rejected = true;
    releaseBody();

    S3ErrorCode errorCode = S3ErrorCode.EntityTooLarge;
    S3Error error = S3Error.builder()
        .code(errorCode.code())
        .message("Your request body exceeds the maximum allowed size of " + maxRequestBodySize + " bytes.")
        .requestId(IdUtils.defaultGenerator().nextStrId())
        .build();
    byte[] content;
    try {
      content = xmlMapper.writeValueAsBytes(error);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }

    FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
        HttpResponseStatus.valueOf(errorCode.httpStatus()), Unpooled.wrappedBuffer(content));
    response.headers()
        .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_XML)
        .set(HttpHeaderNames.CONTENT_LENGTH, content.length)
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

  private void releaseBody() {
    builder = null;
    if (body != null) {
      body.release();
      body = null;
    }
  }

}
