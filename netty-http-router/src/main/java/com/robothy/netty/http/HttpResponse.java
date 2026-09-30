package com.robothy.netty.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.stream.ChunkedInput;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class HttpResponse {

  // Header names are case-insensitive (RFC 9110); a case-sensitive map would let "Content-Length" and
  // "content-length" both be emitted.
  private final Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

  private HttpResponseStatus status;

  // No component limit: consolidating the components of a large body would copy it over and over.
  private final CompositeByteBuf body = Unpooled.compositeBuffer(Integer.MAX_VALUE);

  /**
   * The body that is streamed chunk by chunk instead of the buffered {@link #body}, e.g. a file; {@code null} if the
   * buffered body is sent.
   */
  private ChunkedInput<ByteBuf> chunkedBody;

  public HttpResponse write(String content) {
    if (content != null) {
      write(content.getBytes(StandardCharsets.UTF_8));
    }
    return this;
  }

  public HttpResponse write(byte[] bytes) {
    body.addComponent(true, Unpooled.copiedBuffer(bytes));
    return this;
  }

  public HttpResponse write(ByteBuf buf) {
    body.addComponent(true, buf);
    return this;
  }

  /**
   * Stream the body from {@code input} while it is written to the connection, instead of buffering it in memory. The
   * response takes the input over and closes it once it is written, or dropped, e.g. for a {@code HEAD} request;
   * a previous chunked body is closed. What was {@linkplain #write(ByteBuf) written} to the buffered body isn't sent.
   *
   * <p>The {@code Content-Length} is the {@linkplain ChunkedInput#length() length} of the input unless it is set;
   * an input of unknown length is sent with {@code Transfer-Encoding: chunked}.
   *
   * @param input the response body.
   * @return this response.
   */
  public HttpResponse chunkedBody(ChunkedInput<ByteBuf> input) {
    ChunkedInput<ByteBuf> previous = detachChunkedBody();
    if (previous != null && previous != input) {
      closeQuietly(previous);
    }
    this.chunkedBody = input;
    return this;
  }

  /**
   * Take the chunked body over, e.g. to write it; the caller closes it.
   *
   * @return the chunked body; or {@code null} if there is none.
   */
  public ChunkedInput<ByteBuf> detachChunkedBody() {
    ChunkedInput<ByteBuf> input = this.chunkedBody;
    this.chunkedBody = null;
    return input;
  }

  /**
   * Close {@code input}, logging instead of throwing a failure, as there is nothing left to do about it.
   *
   * @param input the input to close; {@code null} is ignored.
   */
  public static void closeQuietly(ChunkedInput<?> input) {
    if (input == null) {
      return;
    }
    try {
      input.close();
    } catch (Exception e) {
      log.debug("Failed to close the chunked response body.", e);
    }
  }

  public HttpResponse status(HttpResponseStatus status) {
    this.status = status;
    return this;
  }

  public HttpResponse putHeader(String key, Object value) {
    headers.put(key, String.valueOf(value));
    return this;
  }

}
