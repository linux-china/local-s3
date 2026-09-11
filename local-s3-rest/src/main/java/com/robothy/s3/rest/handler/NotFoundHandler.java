package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.rest.utils.ErrorResponses;

/**
 * Responds to requests that no handler matches, i.e. operations that LocalS3 doesn't implement.
 */
class NotFoundHandler implements HttpRequestHandler {

  @Override
  public void handle(HttpRequest request, HttpResponse response) {
    ErrorResponses.notImplemented(request, response);
  }
}
