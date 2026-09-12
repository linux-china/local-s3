package com.robothy.s3.rest.netty;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.utils.ResponseUtils;
import com.robothy.s3.datatypes.response.S3Error;
import io.netty.buffer.ByteBuf;
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
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggregates Netty HTTP messages into an {@linkplain HttpRequest} for the router.
 *
 * <p>The body is limited to {@code maxRequestBodySize} bytes. An oversized request is answered with
 * an S3 {@code EntityTooLarge} error and the connection is closed. When the declared
 * {@code Content-Length} is already too large, the request is rejected before {@code 100 Continue}
 * is sent, so clients that expect it never upload the body.
 *
 * <p>The head of a request with a body is verified by a {@linkplain RequestHeadVerifier}, e.g. its signature,
 * before the body is received. A rejected request is answered with the S3 error of the rejection and the connection
 * is closed, again before {@code 100 Continue} is sent, so that the body of a request that fails anyway is neither
 * uploaded nor buffered. Requests without a body are left to the router, which keeps the connection alive.
 *
 * <p>A body of up to {@code requestBodyFileThreshold} bytes is buffered on the Java heap. A larger body
 * is written to a temporary file, which is memory-mapped as the body of the request, so that large
 * uploads don't take heap memory. The file is deleted once mapped, or when the body is released.
 */
public class LocalS3HttpRequestDecoder extends MessageToMessageDecoder<HttpObject> {

  private static final Logger log = LoggerFactory.getLogger(LocalS3HttpRequestDecoder.class);

  static final String BODY_FILE_PREFIX = "locals3-body-";

  private final long maxRequestBodySize;

  private final long requestBodyFileThreshold;

  private final XmlMapper xmlMapper;

  private final RequestHeadVerifier headVerifier;

  private HttpRequest.HttpRequestBuilder builder;

  /**
   * Buffers the body on the heap until it is written to {@link #bodyFile}.
   */
  private CompositeByteBuf body;

  private Path bodyFile;

  private FileChannel bodyChannel;

  private long receivedBytes;

  private boolean rejected;

  /**
   * Create a decoder that buffers request bodies on the heap.
   *
   * @param maxRequestBodySize max request body size in bytes.
   * @param xmlMapper used to render the error of oversized requests.
   */
  public LocalS3HttpRequestDecoder(long maxRequestBodySize, XmlMapper xmlMapper) {
    this(maxRequestBodySize, Long.MAX_VALUE, xmlMapper);
  }

  /**
   * Create a decoder that accepts the heads of all requests.
   *
   * @param maxRequestBodySize max request body size in bytes.
   * @param requestBodyFileThreshold size in bytes above which a request body is buffered in a temporary file.
   * @param xmlMapper used to render the error of oversized requests.
   */
  public LocalS3HttpRequestDecoder(long maxRequestBodySize, long requestBodyFileThreshold, XmlMapper xmlMapper) {
    this(maxRequestBodySize, requestBodyFileThreshold, xmlMapper, RequestHeadVerifier.ACCEPT_ALL);
  }

  /**
   * Create a decoder.
   *
   * @param maxRequestBodySize max request body size in bytes.
   * @param requestBodyFileThreshold size in bytes above which a request body is buffered in a temporary file.
   * @param xmlMapper used to render the error of rejected requests.
   * @param headVerifier verifies the head of a request with a body before the body is received.
   */
  public LocalS3HttpRequestDecoder(long maxRequestBodySize, long requestBodyFileThreshold, XmlMapper xmlMapper,
                                   RequestHeadVerifier headVerifier) {
    if (maxRequestBodySize <= 0) {
      throw new IllegalArgumentException("maxRequestBodySize must be positive.");
    }
    if (requestBodyFileThreshold < 0) {
      throw new IllegalArgumentException("requestBodyFileThreshold must not be negative.");
    }
    this.maxRequestBodySize = maxRequestBodySize;
    this.requestBodyFileThreshold = requestBodyFileThreshold;
    this.xmlMapper = xmlMapper;
    this.headVerifier = Objects.requireNonNull(headVerifier);
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, HttpObject msg, List<Object> out) throws Exception {
    if (rejected) {
      // The connection is closing after a rejected request; drop whatever is still arriving.
      return;
    }

    try {
      if (msg instanceof io.netty.handler.codec.http.HttpRequest request) {
        releaseBody();
        long contentLength = HttpUtil.getContentLength(request, -1L);
        if (contentLength > maxRequestBodySize) {
          rejectTooLarge(ctx);
          return;
        }
        if (!startRequest(ctx, request, contentLength)) {
          return;
        }
      }

      if (msg instanceof HttpContent content && builder != null) {
        ByteBuf data = content.content();
        receivedBytes += data.readableBytes();
        if (receivedBytes > maxRequestBodySize) {
          rejectTooLarge(ctx);
          return;
        }

        if (bodyChannel == null && receivedBytes > requestBodyFileThreshold) {
          writeBodyToFile();
        }
        if (bodyChannel != null) {
          writeToFile(data);
        } else {
          body.addComponent(true, data.retain());
        }

        if (msg instanceof LastHttpContent) {
          // The body now belongs to the request; LocalS3HttpMessageHandler releases it.
          HttpRequest request = builder.body(takeBody()).build();
          builder = null;
          out.add(request);
        }
      }
    } catch (Exception e) {
      releaseBody();
      throw e;
    }
  }

  /**
   * Start aggregating a request, unless the verifier rejects its head.
   *
   * @return {@code false} if the request is rejected.
   */
  private boolean startRequest(ChannelHandlerContext ctx, io.netty.handler.codec.http.HttpRequest request,
                               long contentLength) throws IOException {
    // A request may repeat a header. AWS Signature Version 4 signs such a header with its values joined by
    // commas, in the order they were received, so keeping only the last one makes the signature of a request
    // with a repeated signed header mismatch. Each value is trimmed, like the canonical headers of SigV4.
    Map<CharSequence, String> headers = new HashMap<>();
    request.headers().forEach(header -> headers.merge(header.getKey().toLowerCase(Locale.ROOT),
        header.getValue().trim(), (values, value) -> values + "," + value));
    QueryStringDecoder queryStringDecoder = new QueryStringDecoder(request.uri());

    builder = HttpRequest.builder()
        .method(request.method())
        .uri(request.uri())
        .httpVersion(request.protocolVersion())
        .headers(headers)
        .path(queryStringDecoder.path())
        .params(new HashMap<>(queryStringDecoder.parameters()));

    if (hasBody(request, contentLength)) {
      RequestHeadVerifier.Rejection rejection = headVerifier.verifyHead(builder.build());
      if (rejection != null) {
        reject(ctx, rejection.errorCode(), rejection.message());
        return false;
      }
    }

    // No component limit: consolidating the components of a large body would copy it over and over.
    body = Unpooled.compositeBuffer(Integer.MAX_VALUE);
    receivedBytes = 0;
    if (contentLength > requestBodyFileThreshold) {
      writeBodyToFile();
    }

    if (HttpHeaderValues.CONTINUE.contentEqualsIgnoreCase(request.headers().get(HttpHeaderNames.EXPECT))) {
      ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
    }
    return true;
  }

  private static boolean hasBody(io.netty.handler.codec.http.HttpRequest request, long contentLength) {
    return contentLength > 0 || HttpUtil.isTransferEncodingChunked(request);
  }

  /**
   * Move the body buffered so far to a temporary file, to which the rest of the body is written.
   */
  private void writeBodyToFile() throws IOException {
    bodyFile = Files.createTempFile(BODY_FILE_PREFIX, ".tmp");
    bodyChannel = FileChannel.open(bodyFile, StandardOpenOption.READ, StandardOpenOption.WRITE);
    writeToFile(body);
    body.release();
    body = null;
  }

  private void writeToFile(ByteBuf data) throws IOException {
    int index = data.readerIndex();
    int end = data.writerIndex();
    while (index < end) {
      index += data.getBytes(index, bodyChannel, end - index);
    }
  }

  private ByteBuf takeBody() throws IOException {
    if (bodyChannel == null) {
      ByteBuf result = body;
      body = null;
      return result;
    }

    ByteBuf mapped = MappedFileByteBuf.map(bodyChannel, bodyFile, receivedBytes);
    // The mapping outlives the channel, and the mapped buffer owns the file now.
    closeBodyFile(false);
    return mapped;
  }

  private void rejectTooLarge(ChannelHandlerContext ctx) {
    reject(ctx, S3ErrorCode.EntityTooLarge,
        "Your request body exceeds the maximum allowed size of " + maxRequestBodySize + " bytes.");
  }

  /**
   * Answer the current request with an S3 error and close the connection.
   */
  private void reject(ChannelHandlerContext ctx, S3ErrorCode errorCode, String message) {
    rejected = true;
    releaseBody();

    // The header and the body of an error report the same request ID, like Amazon S3 does.
    String requestId = ResponseUtils.nextRequestId();
    S3Error error = S3Error.builder()
        .code(errorCode.code())
        .message(message)
        .requestId(requestId)
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
        .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        .set(AmzHeaderNames.X_AMZ_REQUEST_ID, requestId);
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
    closeBodyFile(true);
  }

  private void closeBodyFile(boolean delete) {
    if (bodyChannel != null) {
      try {
        bodyChannel.close();
      } catch (IOException e) {
        log.debug("Failed to close temporary request body file {}.", bodyFile, e);
      }
      bodyChannel = null;
    }
    if (bodyFile != null) {
      if (delete) {
        try {
          Files.deleteIfExists(bodyFile);
        } catch (IOException e) {
          log.warn("Failed to delete temporary request body file {}.", bodyFile, e);
          bodyFile.toFile().deleteOnExit();
        }
      }
      bodyFile = null;
    }
  }

}
