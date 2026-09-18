package com.robothy.s3.rest.handler;


import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.netty.router.ExceptionHandler;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.exception.PreconditionFailedException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.datatypes.response.S3Error;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Parse the {@linkplain LocalS3Exception} to {@linkplain S3Error} and response to the client.
 */
class LocalS3ExceptionHandler implements ExceptionHandler<LocalS3Exception> {

  private final XmlMapper xmlMapper;

  LocalS3ExceptionHandler(ServiceFactory serviceFactory) {
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
  }

  @Override
  public void handle(LocalS3Exception e, HttpRequest request, HttpResponse response) {
    S3ErrorCode s3ErrorCode = e.getS3ErrorCode();
    // The headers and the body of an error report the same request and host IDs, like Amazon S3 does.
    String requestId = ResponseUtils.nextRequestId();
    String hostId = ResponseUtils.nextHostId();
    S3Error error = S3Error.builder()
        .code(s3ErrorCode.code())
        .message(Optional.ofNullable(e.getMessage()).orElse(s3ErrorCode.description()))
        .requestId(requestId)
        .hostId(hostId)
        // What the request named, which Amazon S3 reports in the error rather than in its message. The bucket
        // of the request stands in for the one of an exception that was raised without it, e.g. a NoSuchKey,
        // which Amazon S3 reports with the bucket the key was looked for in.
        .bucketName(Optional.ofNullable(e.getBucketName()).orElseGet(() -> request.parameter("bucket").orElse(null)))
        .key(e.getKey())
        .versionId(e.getVersionId())
        // Amazon S3 names the condition that didn't hold in the error of a conditional request.
        .condition(e instanceof PreconditionFailedException failed ? failed.getCondition() : null)
        .build();

    try {
      response.status(HttpResponseStatus.valueOf(s3ErrorCode.httpStatus()))
          .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML)
          .putHeader(HttpHeaderNames.CONNECTION.toString(), HttpHeaderValues.CLOSE);
      ResponseUtils.addAmzIds(response, requestId, hostId);

      if (!HttpMethod.HEAD.equals(request.getMethod())) {
        response.write(xmlMapper.writeValueAsString(error));
      }
    } catch (JacksonException ex) {
      throw new IllegalStateException(ex);
    }
  }

}
