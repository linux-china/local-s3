package com.robothy.s3.rest.netty;

import com.robothy.netty.http.HttpRequest;

/**
 * Receives every request that a connection answered, once its response is written, e.g. to keep statistics of the
 * requests. Called on the event loop of the connection, so it must be quick and thread-safe.
 */
@FunctionalInterface
public interface RequestRecorder {

  /**
   * Records nothing.
   */
  RequestRecorder NONE = (request, operation, status, requestId, durationNanos) -> {
  };

  /**
   * Record an answered request.
   *
   * @param request the request; its body is released already.
   * @param operation the operation that answered it, see {@linkplain OperationHandler}; {@code "Unknown"} if the
   *     router didn't name it.
   * @param status the status of the response.
   * @param requestId the {@code x-amz-request-id} of the response; {@code null} if it has none.
   * @param durationNanos the time from when the request was handed to the executor until its response was written.
   */
  void record(HttpRequest request, String operation, int status, String requestId, long durationNanos);

}
