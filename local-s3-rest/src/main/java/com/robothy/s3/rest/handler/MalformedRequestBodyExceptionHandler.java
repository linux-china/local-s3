package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.rest.utils.ErrorResponses;
import tools.jackson.core.JacksonException;
import tools.jackson.core.exc.StreamReadException;
import tools.jackson.databind.exc.MismatchedInputException;

/**
 * Handle a {@linkplain JacksonException} that a controller didn't handle. A failure to read a request body, i.e. a
 * document that isn't well-formed ({@linkplain StreamReadException}) or doesn't fit the model of its operation
 * ({@linkplain MismatchedInputException}, e.g. an unknown enum value or an empty body), is an error of the client:
 * {@code 400 MalformedXML}, or {@code ValidationException} for a JSON request. Any other, e.g. a response that can't
 * be written, is a failure of LocalS3, and answered with {@code InternalError} like any unexpected exception.
 */
class MalformedRequestBodyExceptionHandler implements com.robothy.netty.router.ExceptionHandler<JacksonException> {

  @Override
  public void handle(JacksonException e, HttpRequest request, HttpResponse response) {
    if (e instanceof StreamReadException || e instanceof MismatchedInputException) {
      ErrorResponses.malformedBody(request, response);
    } else {
      ErrorResponses.internalError(request, response);
    }
  }

}
