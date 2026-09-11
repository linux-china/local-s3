package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Answers {@code GET} and {@code HEAD} requests to {@value LocalS3Router#HEALTH_CHECK_PATH} with {@code 200 OK}
 * once LocalS3 serves requests, e.g. for Testcontainers wait strategies and Kubernetes probes. The health check
 * needs no authentication.
 */
class HealthCheckController implements HttpRequestHandler {

  private static final String BODY = "{\"status\":\"UP\"}";

  @Override
  public void handle(HttpRequest request, HttpResponse response) {
    response.status(HttpResponseStatus.OK)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON);
    if (!HttpMethod.HEAD.equals(request.getMethod())) {
      response.write(BODY);
    }
  }

}
