package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.datatypes.response.S3Error;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * See {@code docs/apis.md#known-unimplemented-amazon-s3-apis}.
 */
class NotImplementedOperationController implements RouterHttpRequestHandler {

  private final XmlMapper xmlMapper;

  private final String operation;

  public NotImplementedOperationController(ServiceFactory serviceFactory, String operation) {
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
    this.operation = operation;
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {

    String msg = "LocalS3 doesn't support operation '" + operation + "'. "
        + "If you need this feature, please submit an issue at "
        + "https://github.com/Robothy/local-s3/issues/new.";

    // The headers and the body of an error report the same request and host IDs, like Amazon S3 does, so
    // they are generated once here rather than by ResponseUtils.addCommonHeaders.
    String requestId = ResponseUtils.nextRequestId();
    String hostId = ResponseUtils.nextHostId();
    S3Error err = S3Error.builder()
        .code(S3ErrorCode.NotImplemented.code())
        .message(msg)
        .requestId(requestId)
        .hostId(hostId)
        .build();

    response.status(HttpResponseStatus.NOT_IMPLEMENTED)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML)
        .write(xmlMapper.writeValueAsString(err));
    ResponseUtils.addAmzIds(response, requestId, hostId);
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addServerHeader(response);
  }

}