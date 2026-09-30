package com.robothy.s3.rest.handler.s3vectors;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.datatypes.s3vectors.request.ListVectorBucketsRequest;
import com.robothy.s3.datatypes.s3vectors.response.ListVectorBucketsResponse;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.HttpRequestUtils;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP controller for listing vector buckets.
 * Handles POST requests to list all vector buckets owned by the authenticated sender.
 */
@Slf4j
public class ListVectorBucketsController implements RouterHttpRequestHandler {

  private final S3VectorsService s3VectorsService;
  private final ObjectMapper objectMapper;

  public ListVectorBucketsController(ServiceFactory serviceFactory) {
    this.s3VectorsService = serviceFactory.getInstance(S3VectorsService.class);
    this.objectMapper = serviceFactory.getInstance(ObjectMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    ListVectorBucketsRequest listRequest = parseRequest(request);
    ListVectorBucketsResponse listResponse = processRequest(listRequest);
    HttpRequestUtils.sendJsonResponse(response, listResponse, objectMapper);
  }

  private ListVectorBucketsRequest parseRequest(RouterHttpRequest request) throws Exception {
    byte[] bodyBytes = HttpRequestUtils.extractRequestBody(request);
    return HttpRequestUtils.parseRequestOrDefault(bodyBytes, ListVectorBucketsRequest.class, objectMapper);
  }

  private ListVectorBucketsResponse processRequest(ListVectorBucketsRequest listRequest) {
    return s3VectorsService.listVectorBuckets(
        listRequest.getMaxResults(),
        listRequest.getNextToken(),
        listRequest.getPrefix()
    );
  }

}
