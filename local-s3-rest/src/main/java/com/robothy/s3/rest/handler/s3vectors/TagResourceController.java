package com.robothy.s3.rest.handler.s3vectors;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.datatypes.s3vectors.request.TagResourceRequest;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.HttpRequestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Handle S3 Vectors TagResource operation, {@code POST /tags/{resourceArn}}.
 */
public class TagResourceController implements HttpRequestHandler {

  private final S3VectorsService s3VectorsService;
  private final ObjectMapper objectMapper;

  public TagResourceController(ServiceFactory serviceFactory) {
    this.s3VectorsService = serviceFactory.getInstance(S3VectorsService.class);
    this.objectMapper = serviceFactory.getInstance(ObjectMapper.class);
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
    byte[] bodyBytes = HttpRequestUtils.extractRequestBody(request);
    TagResourceRequest tagRequest =
        HttpRequestUtils.parseRequiredRequest(bodyBytes, TagResourceRequest.class, objectMapper);
    s3VectorsService.tagResource(VectorResourceRequests.resource(request), tagRequest.getTags());
    HttpRequestUtils.sendEmptyJsonResponse(response);
  }

}
