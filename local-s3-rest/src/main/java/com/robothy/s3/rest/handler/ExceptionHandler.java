package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.rest.utils.ErrorResponses;

/**
 * Handle any unhandled exceptions with an {@code InternalError}. The response doesn't reveal the
 * exception, which {@linkplain com.robothy.s3.rest.netty.LocalS3HttpMessageHandler} logs.
 */
class ExceptionHandler implements com.robothy.netty.router.ExceptionHandler<Exception> {

  @Override
  public void handle(Exception e, HttpRequest request, HttpResponse response) {
    ErrorResponses.internalError(request, response);
  }

}
