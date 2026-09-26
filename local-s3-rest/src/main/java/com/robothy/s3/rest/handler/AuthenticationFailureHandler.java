package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.rest.utils.ResponseUtils;
import com.robothy.s3.datatypes.response.S3Error;
import com.robothy.s3.rest.utils.XmlUtils;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Writes an S3-compatible response for a failed authentication attempt.
 */
final class AuthenticationFailureHandler implements HttpRequestHandler {

  private final AwsSignatureV4Verifier.VerificationResult result;

  AuthenticationFailureHandler(AwsSignatureV4Verifier.VerificationResult result) {
    this.result = result;
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) {
    // The headers and the body of an error report the same request and host IDs, like Amazon S3 does.
    String requestId = ResponseUtils.nextRequestId();
    String hostId = ResponseUtils.nextHostId();
    S3Error error = S3Error.builder()
        .code(result.errorCode().code())
        .message(result.message())
        .clientComputedContentSha256(result.clientComputedContentSha256())
        .s3ComputedContentSha256(result.s3ComputedContentSha256())
        .requestId(requestId)
        .hostId(hostId)
        .build();

    response.status(HttpResponseStatus.valueOf(result.errorCode().httpStatus()))
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML)
        .putHeader(HttpHeaderNames.CONNECTION.toString(), HttpHeaderValues.CLOSE);
    ResponseUtils.addAmzIds(response, requestId, hostId);
    if (!HttpMethod.HEAD.equals(request.getMethod())) {
      response.write(XmlUtils.toXml(error));
    }
  }
}
