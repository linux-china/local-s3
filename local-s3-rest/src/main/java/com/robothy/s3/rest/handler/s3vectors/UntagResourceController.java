package com.robothy.s3.rest.handler.s3vectors;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.HttpRequestUtils;

/**
 * Handle S3 Vectors UntagResource operation, {@code DELETE /tags/{resourceArn}?tagKeys=...}, whose keys are repeated
 * query parameters.
 */
public class UntagResourceController implements RouterHttpRequestHandler {

  private final S3VectorsService s3VectorsService;

  public UntagResourceController(ServiceFactory serviceFactory) {
    this.s3VectorsService = serviceFactory.getInstance(S3VectorsService.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) {
    s3VectorsService.untagResource(VectorResourceRequests.resource(request), request.getParams().get("tagKeys"));
    HttpRequestUtils.sendEmptyJsonResponse(response);
  }

}
