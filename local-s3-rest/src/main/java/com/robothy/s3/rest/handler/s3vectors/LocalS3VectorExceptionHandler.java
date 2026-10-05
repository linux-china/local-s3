package com.robothy.s3.rest.handler.s3vectors;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.netty.router.ExceptionHandler;
import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import com.robothy.s3.datatypes.s3vectors.response.S3VectorsError;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.service.ServiceFactory;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

public class LocalS3VectorExceptionHandler implements ExceptionHandler<LocalS3VectorException> {

  private static final Logger log = LoggerFactory.getLogger(LocalS3VectorExceptionHandler.class);

  private final ServiceFactory serviceFactory;

  public LocalS3VectorExceptionHandler(ServiceFactory serviceFactory) {
    this.serviceFactory = serviceFactory;
  }

  @Override
  public void handle(LocalS3VectorException e, RouterHttpRequest httpRequest, RouterHttpResponse httpResponse) {
    if (e.getErrorType().getStatus() >= 500) {
      // Not a rejected request but a failure of the service, e.g. lost vector data, which must not go unnoticed.
      log.error("Failed to handle {} {}.", httpRequest.getMethod(), httpRequest.getUri(), e);
    }
    httpResponse.status(HttpResponseStatus.valueOf(e.getErrorType().getStatus()))
        .putHeader(AmzHeaderNames.X_AMZN_ERRORTYPE, e.getErrorType().getCode())
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), "application/json");
    ObjectMapper objectMapper = this.serviceFactory.getInstance(ObjectMapper.class);
    try {
      httpResponse.write(objectMapper.writer()
          .writeValueAsString(new S3VectorsError(e.getMessage())));
    } catch (JacksonException _) {
      httpResponse.status(HttpResponseStatus.INTERNAL_SERVER_ERROR);
      httpResponse.write("Internal Server Error");
    }
  }
}
