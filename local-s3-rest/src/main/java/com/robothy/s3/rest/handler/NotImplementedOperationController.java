package com.robothy.s3.rest.handler;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.datatypes.response.S3Error;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Answers {@code 501 NotImplemented} for an operation that LocalS3 routes but doesn't implement. The
 * README lists them, so that a user can tell before choosing LocalS3 whether it covers what their tests
 * need.
 */
class NotImplementedOperationController implements HttpRequestHandler {

  private final XmlMapper xmlMapper;

  private final String operation;

  public NotImplementedOperationController(ServiceFactory serviceFactory, String operation) {
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
    this.operation = operation;
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {

    String msg = "LocalS3 doesn't support operation '" + operation + "'. "
        + "If you need this feature, please submit an issue at "
        + "https://github.com/Robothy/local-s3/issues/new.";

    // The header and the body of an error report the same request ID, like Amazon S3 does, so
    // the ID is generated once here rather than by ResponseUtils.addCommonHeaders.
    String requestId = ResponseUtils.nextRequestId();
    S3Error err = S3Error.builder()
        .code(S3ErrorCode.NotImplemented.code())
        .message(msg)
        .requestId(requestId)
        .build();

    response.status(HttpResponseStatus.NOT_IMPLEMENTED)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML)
        .write(xmlMapper.writeValueAsString(err));
    ResponseUtils.addAmzRequestId(response, requestId);
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addServerHeader(response);
  }

}