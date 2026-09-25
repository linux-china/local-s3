package com.robothy.s3.rest.utils;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.exception.vectors.LocalS3VectorErrorType;
import com.robothy.s3.datatypes.response.S3Error;
import com.robothy.s3.datatypes.s3vectors.response.S3VectorsError;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Locale;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes error responses in the format that AWS SDKs parse: an S3 {@code <Error>} document, or, for
 * S3 Vectors requests, which send JSON, a JSON error named by the {@code x-amzn-errortype} header.
 */
public final class ErrorResponses {

  private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults().build();

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

  /**
   * Respond to a request whose body can't be read as the document of its operation, e.g. XML that isn't well-formed
   * or an element whose value doesn't fit. Amazon S3 answers it with {@code 400 MalformedXML}, and S3 Vectors with
   * {@code 400 ValidationException}. It is an error of the client, which an AWS SDK doesn't retry, unlike the
   * {@code InternalError} it would otherwise be.
   *
   * @param request the request.
   * @param response the response to write the error to.
   */
  public static void malformedBody(HttpRequest request, HttpResponse response) {
    if (isJsonRequest(request)) {
      writeVectorsError(response, LocalS3VectorErrorType.VALIDATION, "The request body is not valid JSON of the "
          + "operation.");
    } else {
      writeS3Error(request, response, S3ErrorCode.MalformedXML, S3ErrorCode.MalformedXML.description());
    }
  }

  private static void writeS3Error(HttpRequest request, HttpResponse response, S3ErrorCode errorCode,
                                   String message) {
    // The headers and the body of an error report the same request and host IDs, like Amazon S3 does.
    String requestId = ResponseUtils.nextRequestId();
    String hostId = ResponseUtils.nextHostId();
    response.status(HttpResponseStatus.valueOf(errorCode.httpStatus()))
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML);
    ResponseUtils.addAmzIds(response, requestId, hostId);
    // A response to a HEAD request has no body.
    if (request == null || !HttpMethod.HEAD.equals(request.getMethod())) {
      response.write(XmlUtils.toXml(S3Error.builder()
          .code(errorCode.code())
          .message(message)
          .requestId(requestId)
          .hostId(hostId)
          .build()));
    }
  }

  private static void writeVectorsError(HttpResponse response, LocalS3VectorErrorType errorType, String message) {
    response.status(HttpResponseStatus.valueOf(errorType.getStatus()))
        .putHeader(AmzHeaderNames.X_AMZN_ERRORTYPE, errorType.getCode())
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON);
    try {
      response.write(JSON.writeValueAsString(S3VectorsError.builder().message(message).build()));
    } catch (JacksonException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean isJsonRequest(HttpRequest request) {
    return request != null && request.header(HttpHeaderNames.CONTENT_TYPE.toString())
        .map(contentType -> contentType.toLowerCase(Locale.ROOT).startsWith("application/json"))
        .orElse(false);
  }

}
