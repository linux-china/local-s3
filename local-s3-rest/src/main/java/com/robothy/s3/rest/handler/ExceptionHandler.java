package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.rest.utils.ErrorResponses;

/**
 * Handle any unhandled exceptions with an {@code InternalError}. The response doesn't reveal the
 * exception, which {@linkplain com.robothy.s3.rest.netty.LocalS3HttpMessageHandler} logs.
 *
 * <p>Registered for {@link Throwable} rather than {@link Exception}, so that the router never falls back to the
 * default handler of netty-http-router, which writes the stack trace of an {@link Error} to the response body.
 */
class ExceptionHandler implements com.robothy.netty.router.ExceptionHandler<Throwable> {

  @Override
  public void handle(Throwable e, RouterHttpRequest request, RouterHttpResponse response) {
    ErrorResponses.internalError(request, response);
  }

}
