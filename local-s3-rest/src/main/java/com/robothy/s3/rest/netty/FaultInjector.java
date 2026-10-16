package com.robothy.s3.rest.netty;

import com.robothy.netty.http.RouterHttpRequest;
import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Decides whether {@linkplain LocalS3HttpMessageHandler} fails a request on purpose, once the router has named its
 * operation; see {@code docs/embedding.md#inject-faults}. Called on the thread that handles the request, so it must be
 * thread-safe.
 */
@FunctionalInterface
public interface FaultInjector {

  /**
   * Fails nothing.
   */
  FaultInjector NONE = (operation, request) -> null;

  /**
   * The fault to inject into a request.
   *
   * @param operation the operation that the router named, see {@linkplain OperationHandler}.
   * @param request the request, with the parameters that the router set, e.g. {@code bucket} and {@code key}.
   * @return the fault; {@code null} to handle the request as usual.
   */
  @Nullable Injection inject(String operation, RouterHttpRequest request);

  /**
   * What is done to a request.
   *
   * @param delay how long to wait before the request is handled; zero for no delay.
   * @param error the error to answer instead of handling the request; {@code null} to handle it.
   * @param closeConnection whether to close the connection instead of handling the request.
   * @param resetAfterBytes the bytes of the response body after which the connection is closed; negative to send it
   *     all.
   * @param bytesPerSecond how fast the response is sent at most; {@code 0} for no limit.
   */
  record Injection(Duration delay, @Nullable Error error, boolean closeConnection, long resetAfterBytes,
                   long bytesPerSecond) {

    public Injection {
      Objects.requireNonNull(delay);
    }

    boolean shapesResponse() {
      return resetAfterBytes >= 0 || bytesPerSecond > 0;
    }

  }

  /**
   * An error response.
   *
   * @param status the HTTP status.
   * @param code the error code, e.g. {@code SlowDown}.
   * @param message the message of the error.
   */
  record Error(int status, String code, String message) {

    public Error {
      Objects.requireNonNull(code);
      Objects.requireNonNull(message);
    }

  }

}
