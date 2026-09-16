package com.robothy.s3.rest.netty;

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
import io.netty.handler.codec.DecoderResult;
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
import io.netty.handler.codec.http.TooLongHttpHeaderException;
import io.netty.handler.codec.http.TooLongHttpLineException;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.dataformat.xml.XmlMapper;

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
 * <p>A request that the HTTP codec fails to decode, e.g. one whose header section exceeds the max header size, is
 * answered with an S3 error, {@code RequestHeaderSectionTooLarge} for a header section that is too large and
 * {@code BadRequest} otherwise, and the connection is closed: the codec discards the rest of the connection's
 * input after a failure, so the request would otherwise never be answered.
 *
 * <p>A body of up to {@code requestBodyFileThreshold} bytes is buffered on the Java heap. A larger body
 * is written to a temporary file, which is memory-mapped as the body of the request, so that large
 * uploads don't take heap memory. The file is kept while the body is alive, so that a handler can hand it over to a
 * storage instead of copying the body (see {@linkplain RequestBodies#file}), and is deleted when the body is released.
 * The files are created in the configured directory, e.g. one on the file system of the storage, which then renames a
 * file into place, or in the default temporary directory.
 *
 * <p>The event loop never waits for the disk: a {@linkplain RequestBodyFile} writes the body on the body file executor,
 * and the event loop only queues its chunks. While more than {@value RequestBodyFile#HIGH_WATER_MARK} bytes wait to be
 * written, the connection isn't read, so that a client that sends faster than the disk writes neither fills the memory
 * nor holds up the other connections of the event loop. Once the last chunk is received, the connection isn't read
 * until the file is complete; the messages already decoded meanwhile, e.g. a pipelined request, are held and decoded
 * after the request, so that the requests of a connection keep their order.
 */
public class LocalS3HttpRequestDecoder extends MessageToMessageDecoder<HttpObject> {

  private static final Logger log = LoggerFactory.getLogger(LocalS3HttpRequestDecoder.class);

  static final String BODY_FILE_PREFIX = "locals3-body-";

  private final long maxRequestBodySize;

  private final long requestBodyFileThreshold;

  private final XmlMapper xmlMapper;

  private final RequestHeadVerifier headVerifier;

  /**
   * The directory that the temporary body files are created in; {@code null} for the default temporary directory.
   */
  private final Path bodyFileDirectory;

  /**
   * Runs the operations on the temporary body files.
   */
  private final Executor bodyFileExecutor;

  private HttpRequest.HttpRequestBuilder builder;

  /**
   * The head of the current request, if the verifier accepted it; handed to the verifier with the complete request.
   */
  private HttpRequest verifiedHead;

  /**
   * Buffers the body on the heap until it is written to {@link #bodyFile}.
   */
  private CompositeByteBuf body;

  private RequestBodyFile bodyFile;

  /**
   * Whether more bytes of {@link #bodyFile} wait to be written than it should hold.
   */
  private boolean bodyFileBacklogged;

  /**
   * Whether the last chunk of the body was received, and {@link #bodyFile} is being completed.
   */
  private boolean completingBody;

  /**
   * The messages decoded while {@link #completingBody}, which are decoded once the request is complete.
   */
  private final Queue<HttpObject> heldMessages = new ArrayDeque<>();

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
    this(maxRequestBodySize, requestBodyFileThreshold, xmlMapper, headVerifier, null);
  }

  /**
   * Create a decoder.
   *
   * @param maxRequestBodySize max request body size in bytes.
   * @param requestBodyFileThreshold size in bytes above which a request body is buffered in a temporary file.
   * @param xmlMapper used to render the error of rejected requests.
   * @param headVerifier verifies the head of a request with a body before the body is received.
   * @param bodyFileDirectory the directory that the temporary body files are created in, which must exist;
   *     {@code null} for the default temporary directory.
   */
  public LocalS3HttpRequestDecoder(long maxRequestBodySize, long requestBodyFileThreshold, XmlMapper xmlMapper,
                                   RequestHeadVerifier headVerifier, Path bodyFileDirectory) {
    this(maxRequestBodySize, requestBodyFileThreshold, xmlMapper, headVerifier, bodyFileDirectory, Runnable::run);
  }

  /**
   * Create a decoder.
   *
   * @param maxRequestBodySize max request body size in bytes.
   * @param requestBodyFileThreshold size in bytes above which a request body is buffered in a temporary file.
   * @param xmlMapper used to render the error of rejected requests.
   * @param headVerifier verifies the head of a request with a body before the body is received.
   * @param bodyFileDirectory the directory that the temporary body files are created in, which must exist;
   *     {@code null} for the default temporary directory.
   * @param bodyFileExecutor runs the operations on the temporary body files, e.g. the executor of the requests, so
   *     that the event loop doesn't wait for the disk; {@code Runnable::run} runs them on the event loop.
   */
  public LocalS3HttpRequestDecoder(long maxRequestBodySize, long requestBodyFileThreshold, XmlMapper xmlMapper,
                                   RequestHeadVerifier headVerifier, Path bodyFileDirectory,
                                   Executor bodyFileExecutor) {
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
    this.bodyFileDirectory = bodyFileDirectory;
    this.bodyFileExecutor = Objects.requireNonNull(bodyFileExecutor);
  }

  /**
   * Prepare a directory for the temporary body files: create it if it doesn't exist, and delete the body files that a
   * process which died while it received requests left behind in it.
   *
   * @param directory the directory.
   * @throws IOException if the directory can't be created or cleaned.
   */
  public static void prepareBodyFileDirectory(Path directory) throws IOException {
    Files.createDirectories(directory);
    try (DirectoryStream<Path> leftovers = Files.newDirectoryStream(directory, BODY_FILE_PREFIX + "*.tmp")) {
      for (Path leftover : leftovers) {
        Files.deleteIfExists(leftover);
      }
    }
  }

  @Override
  protected void decode(ChannelHandlerContext ctx, HttpObject msg, List<Object> out) {
    if (rejected) {
      // The connection is closing after a rejected request; drop whatever is still arriving.
      return;
    }
    if (completingBody) {
      // Released once it is decoded, or when the connection closes.
      heldMessages.add(ReferenceCountUtil.retain(msg));
      return;
    }
    decodeMessage(ctx, msg);
  }

  /**
   * Decode a message, and hand a complete request to the next handler.
   */
  private void decodeMessage(ChannelHandlerContext ctx, HttpObject msg) {
    DecoderResult decoderResult = msg.decoderResult();
    if (decoderResult.isFailure()) {
      rejectMalformed(ctx, decoderResult.cause());
      return;
    }

    try {
      if (msg instanceof io.netty.handler.codec.http.HttpRequest request) {
        releaseBody(ctx);
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

        if (bodyFile == null && receivedBytes > requestBodyFileThreshold) {
          writeBodyToFile(ctx);
        }
        if (bodyFile != null) {
          if (bodyFile.write(data.retain()) && !bodyFileBacklogged) {
            bodyFileBacklogged = true;
            ReadSuspensions.suspend(ctx.channel(), this);
          }
        } else {
          body.addComponent(true, data.retain());
        }

        if (msg instanceof LastHttpContent) {
          if (bodyFile != null) {
            // The request is handed on once the file is complete, see bodyFileCompleted().
            completingBody = true;
            ReadSuspensions.suspend(ctx.channel(), this);
            bodyFile.complete();
          } else {
            ByteBuf heapBody = body;
            body = null;
            requestReceived(ctx, heapBody);
          }
        }
      }
    } catch (RuntimeException e) {
      releaseBody(ctx);
      throw e;
    }
  }

  /**
   * Hand the request whose body is complete to the next handler.
   */
  private void requestReceived(ChannelHandlerContext ctx, ByteBuf requestBody) {
    // The body now belongs to the request; LocalS3HttpMessageHandler releases it.
    HttpRequest request = builder.body(requestBody).build();
    builder = null;
    if (verifiedHead != null) {
      HttpRequest head = verifiedHead;
      verifiedHead = null;
      try {
        headVerifier.requestReceived(head, request);
      } catch (RuntimeException e) {
        requestBody.release();
        throw e;
      }
    }
    ctx.fireChannelRead(request);
  }

  private void bodyFileCompleted(ChannelHandlerContext ctx, ByteBuf mappedBody) {
    bodyFile = null;
    completingBody = false;
    bodyFileBacklogged = false;
    try {
      requestReceived(ctx, mappedBody);
    } catch (RuntimeException e) {
      releaseBody(ctx);
      ctx.fireExceptionCaught(e);
      return;
    }
    ReadSuspensions.resume(ctx.channel(), this);
    decodeHeldMessages(ctx);
  }

  private void bodyFileFailed(ChannelHandlerContext ctx, Throwable cause) {
    log.warn("Failed to write the body of a request on connection {} to a temporary file.", ctx.channel().id(),
        cause);
    reject(ctx, S3ErrorCode.InternalError, S3ErrorCode.InternalError.description());
  }

  private void bodyFileDrained(ChannelHandlerContext ctx) {
    bodyFileBacklogged = false;
    if (!completingBody) {
      ReadSuspensions.resume(ctx.channel(), this);
    }
  }

  /**
   * Decode the messages held while a body file was completed, until another one is.
   */
  private void decodeHeldMessages(ChannelHandlerContext ctx) {
    HttpObject msg;
    while (!completingBody && !rejected && (msg = heldMessages.poll()) != null) {
      try {
        decodeMessage(ctx, msg);
      } catch (RuntimeException e) {
        ctx.fireExceptionCaught(e);
      } finally {
        ReferenceCountUtil.release(msg);
      }
    }
    if (rejected) {
      releaseHeldMessages();
    }
  }

  /**
   * Start aggregating a request, unless the verifier rejects its head.
   *
   * @return {@code false} if the request is rejected.
   */
  private boolean startRequest(ChannelHandlerContext ctx, io.netty.handler.codec.http.HttpRequest request,
                               long contentLength) {
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
      HttpRequest head = builder.build();
      RequestHeadVerifier.Rejection rejection = headVerifier.verifyHead(head);
      if (rejection != null) {
        reject(ctx, rejection.errorCode(), rejection.message());
        return false;
      }
      verifiedHead = head;
    }

    // No component limit: consolidating the components of a large body would copy it over and over.
    body = Unpooled.compositeBuffer(Integer.MAX_VALUE);
    receivedBytes = 0;
    if (contentLength > requestBodyFileThreshold) {
      writeBodyToFile(ctx);
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
  private void writeBodyToFile(ChannelHandlerContext ctx) {
    RequestBodyFile file = new RequestBodyFile(bodyFileExecutor, ctx.executor(), bodyFileDirectory,
        new RequestBodyFile.Listener() {
          @Override
          public void drained() {
            bodyFileDrained(ctx);
          }

          @Override
          public void completed(ByteBuf mappedBody) {
            bodyFileCompleted(ctx, mappedBody);
          }

          @Override
          public void failed(Throwable cause) {
            bodyFileFailed(ctx, cause);
          }
        });
    bodyFile = file;
    CompositeByteBuf buffered = body;
    body = null;
    file.write(buffered);
  }

  /**
   * Answer a request that the HTTP codec failed to decode. The cause is logged, but not revealed to the client.
   */
  private void rejectMalformed(ChannelHandlerContext ctx, Throwable cause) {
    log.debug("Rejecting a malformed request on connection {}: {}", ctx.channel().id(), String.valueOf(cause));
    if (cause instanceof TooLongHttpHeaderException) {
      reject(ctx, S3ErrorCode.RequestHeaderSectionTooLarge, S3ErrorCode.RequestHeaderSectionTooLarge.description());
    } else if (cause instanceof TooLongHttpLineException) {
      reject(ctx, S3ErrorCode.BadRequest, "The request line exceeds the maximum allowed length.");
    } else {
      reject(ctx, S3ErrorCode.BadRequest, "An error occurred when parsing the HTTP request.");
    }
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
    releaseBody(ctx);
    releaseHeldMessages();

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
    } catch (JacksonException e) {
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

  /**
   * Unlike {@linkplain MessageToMessageDecoder}, don't read on after a read that produced no request while reading is
   * suspended: the decoder hands its requests on directly, and a body chunk queued for its file produces none, so
   * reading on would defeat the suspension of the decoder, while the body file is backlogged or completed, and of
   * {@linkplain LocalS3HttpMessageHandler}, while a request is in flight. Reading is only suspended through
   * {@linkplain ReadSuspensions}, and resumed by whoever suspended it.
   */
  @Override
  public void channelReadComplete(ChannelHandlerContext ctx) {
    ctx.fireChannelReadComplete();
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    releaseBody(ctx);
    releaseHeldMessages();
    super.channelInactive(ctx);
  }

  @Override
  public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
    releaseBody(ctx);
    releaseHeldMessages();
    super.handlerRemoved(ctx);
  }

  /**
   * Give the body of the current request up, and stop suspending reading for it.
   */
  private void releaseBody(ChannelHandlerContext ctx) {
    builder = null;
    verifiedHead = null;
    if (body != null) {
      body.release();
      body = null;
    }
    if (bodyFile != null) {
      bodyFile.discard();
      bodyFile = null;
    }
    if (bodyFileBacklogged || completingBody) {
      bodyFileBacklogged = false;
      completingBody = false;
      ReadSuspensions.resume(ctx.channel(), this);
    }
  }

  private void releaseHeldMessages() {
    HttpObject msg;
    while ((msg = heldMessages.poll()) != null) {
      ReferenceCountUtil.release(msg);
    }
  }

}
