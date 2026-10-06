package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * See {@code docs/deployment.md#health-check}.
 */
class HealthCheckController implements RouterHttpRequestHandler {

  private static final String BODY = "{\"status\":\"UP\"}";

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) {
    response.status(HttpResponseStatus.OK)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON);
    if (!HttpMethod.HEAD.equals(request.getMethod())) {
      response.write(BODY);
    }
  }

}
