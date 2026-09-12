package com.robothy.s3.rest.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.exception.vectors.LocalS3VectorErrorType;
import com.robothy.s3.core.util.IdUtils;
import com.robothy.s3.datatypes.response.S3Error;
import com.robothy.s3.datatypes.s3vectors.response.S3VectorsError;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Locale;

/**
 * Writes error responses in the format that AWS SDKs parse: an S3 {@code <Error>} document, or, for
 * S3 Vectors requests, which send JSON, a JSON error named by the {@code x-amzn-errortype} header.
 */
public final class ErrorResponses {

  private static final ObjectMapper JSON = new ObjectMapper();

  private ErrorResponses() {
  }

  /**
   * Respond to a request that no handler matches, i.e. an operation that LocalS3 doesn't implement.
   *
   * @param request the request.
   * @param response the response to write the error to.
   */
  public static void notImplemented(HttpRequest request, HttpResponse response) {
    String message = "LocalS3 does not implement " + request.getMethod() + " " + request.getPath() + ".";
    if (isJsonRequest(request)) {
      writeVectorsError(response, LocalS3VectorErrorType.NOT_FOUND, message);
    } else {
      writeS3Error(request, response, S3ErrorCode.NotImplemented, message);
    }
  }

  /**
   * Respond to an unexpected failure. The response doesn't reveal the cause, which should be logged instead.
   *
   * @param request the failed request; {@code null} if it wasn't decoded.
   * @param response the response to write the error to.
   */
  public static void internalError(HttpRequest request, HttpResponse response) {
    String message = S3ErrorCode.InternalError.description();
    if (isJsonRequest(request)) {
      writeVectorsError(response, LocalS3VectorErrorType.INTERNAL_SERVER_ERROR, message);
    } else {
      writeS3Error(request, response, S3ErrorCode.InternalError, message);
    }
  }

  private static void writeS3Error(HttpRequest request, HttpResponse response, S3ErrorCode errorCode,
                                   String message) {
    String requestId = ResponseUtils.nextRequestId();
    response.status(HttpResponseStatus.valueOf(errorCode.httpStatus()))
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML)
        .putHeader(AmzHeaderNames.X_AMZ_REQUEST_ID, requestId);
    // A response to a HEAD request has no body.
    if (request == null || !HttpMethod.HEAD.equals(request.getMethod())) {
      response.write(XmlUtils.toXml(S3Error.builder()
          .code(errorCode.code())
          .message(message)
          .requestId(requestId)
          .build()));
    }
  }

  private static void writeVectorsError(HttpResponse response, LocalS3VectorErrorType errorType, String message) {
    response.status(HttpResponseStatus.valueOf(errorType.getStatus()))
        .putHeader(AmzHeaderNames.X_AMZN_ERRORTYPE, errorType.getCode())
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON);
    try {
      response.write(JSON.writeValueAsString(S3VectorsError.builder().message(message).build()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean isJsonRequest(HttpRequest request) {
    return request != null && request.header(HttpHeaderNames.CONTENT_TYPE.toString())
        .map(contentType -> contentType.toLowerCase(Locale.ROOT).startsWith("application/json"))
        .orElse(false);
  }

}
