package com.robothy.s3.rest.handler.s3vectors;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.datatypes.s3vectors.response.ListTagsForResourceResponse;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.HttpRequestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Handle S3 Vectors ListTagsForResource operation, {@code GET /tags/{resourceArn}}.
 */
public class ListTagsForResourceController implements HttpRequestHandler {

  private final S3VectorsService s3VectorsService;
  private final ObjectMapper objectMapper;

  public ListTagsForResourceController(ServiceFactory serviceFactory) {
    this.s3VectorsService = serviceFactory.getInstance(S3VectorsService.class);
    this.objectMapper = serviceFactory.getInstance(ObjectMapper.class);
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
    ListTagsForResourceResponse listResponse =
        s3VectorsService.listTagsForResource(VectorResourceRequests.resource(request));
    HttpRequestUtils.sendJsonResponse(response, listResponse, objectMapper);
  }

}
