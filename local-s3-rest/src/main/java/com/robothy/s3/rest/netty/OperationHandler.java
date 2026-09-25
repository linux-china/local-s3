package com.robothy.s3.rest.netty;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import java.util.Objects;

/**
 * A handler that a router matched to a request, with the name of the operation it answers, e.g.
 * {@code PutObject}, so that {@linkplain LocalS3HttpMessageHandler} records the request by its operation.
 *
 * @param operation the name of the operation.
 * @param handler the handler of the operation.
 */
public record OperationHandler(String operation, HttpRequestHandler handler) implements HttpRequestHandler {

  /**
   * The operation of a request that no router named.
   */
  public static final String UNKNOWN_OPERATION = "Unknown";

  /**
   * The operation of a request that no route of a router matches, which its fallback handler answers.
   */
  public static final String NOT_FOUND_OPERATION = "NotFound";

  public OperationHandler {
    Objects.requireNonNull(operation);
    Objects.requireNonNull(handler);
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
    handler.handle(request, response);
  }

  /**
   * The operation of a matched handler.
   *
   * @param handler the handler; may be {@code null}.
   * @return the name of its operation; {@linkplain #UNKNOWN_OPERATION} if it isn't an {@linkplain OperationHandler}.
   */
  static String operationOf(HttpRequestHandler handler) {
    return handler instanceof OperationHandler named ? named.operation() : UNKNOWN_OPERATION;
  }

}
