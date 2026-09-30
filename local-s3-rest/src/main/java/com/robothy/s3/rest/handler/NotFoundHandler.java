package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.rest.utils.ErrorResponses;

/**
 * Responds to requests that no handler matches, i.e. operations that LocalS3 doesn't implement.
 */
class NotFoundHandler implements RouterHttpRequestHandler {

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) {
    ErrorResponses.notImplemented(request, response);
  }
}
