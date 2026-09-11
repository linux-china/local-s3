package com.robothy.s3.rest.netty;

import com.robothy.netty.http.HttpResponse;
import java.io.IOException;
import java.io.InputStream;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@linkplain HttpResponse} whose body can be streamed from an {@linkplain InputStream}
 * instead of being buffered in memory.
 */
public class StreamingHttpResponse extends HttpResponse {

  private static final Logger log = LoggerFactory.getLogger(StreamingHttpResponse.class);

  private InputStream bodyStream;

  /**
   * Send the response body from the given stream. The stream is read chunk by chunk while it is
   * written to the connection, and closed afterwards. Set the {@code Content-Length} header when
   * the length is known; otherwise the body is sent with chunked transfer encoding.
   *
   * @param bodyStream the response body.
   * @return this response.
   */
  public StreamingHttpResponse stream(@NonNull InputStream bodyStream) {
    closeBodyStream();
    this.bodyStream = bodyStream;
    return this;
  }

  /**
   * Whether the response body is sent from a stream.
   *
   * @return {@code true} if a body stream is attached.
   */
  public boolean isStreaming() {
    return bodyStream != null;
  }

  InputStream getBodyStream() {
    return bodyStream;
  }

  /**
   * Drop this response without sending it, e.g. when the handler failed after filling it.
   */
  void discard() {
    closeBodyStream();
    getBody().release();
  }

  private void closeBodyStream() {
    if (bodyStream == null) {
      return;
    }

    try {
      bodyStream.close();
    } catch (IOException e) {
      log.debug("Failed to close response body stream.", e);
    }
    bodyStream = null;
  }

}
