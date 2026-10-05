package com.robothy.s3.rest.handler.s3vectors;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.core.util.S3VectorsArnUtils;
import com.robothy.s3.datatypes.s3vectors.request.ListIndexesRequest;
import com.robothy.s3.datatypes.s3vectors.response.ListIndexesResponse;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.HttpRequestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP controller for listing vector indexes within a vector bucket.
 * Handles POST requests to list all vector indexes in the specified bucket.
 */
public class ListIndexesController implements RouterHttpRequestHandler {

  private static final Logger log = LoggerFactory.getLogger(ListIndexesController.class);

  private final S3VectorsService s3VectorsService;
  private final ObjectMapper objectMapper;

  public ListIndexesController(ServiceFactory serviceFactory) {
    this.s3VectorsService = serviceFactory.getInstance(S3VectorsService.class);
    this.objectMapper = serviceFactory.getInstance(ObjectMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    ListIndexesRequest listRequest = parseRequest(request);
    ListIndexesResponse listResponse = processRequest(listRequest);
    HttpRequestUtils.sendJsonResponse(response, listResponse, objectMapper);
  }

  private ListIndexesRequest parseRequest(RouterHttpRequest request) throws Exception {
    byte[] bodyBytes = HttpRequestUtils.extractRequestBody(request);
    return HttpRequestUtils.parseRequestOrDefault(bodyBytes, ListIndexesRequest.class, objectMapper);
  }

  private ListIndexesResponse processRequest(ListIndexesRequest listRequest) {
    String vectorBucketName = S3VectorsArnUtils.resolveBucketName(
        listRequest.getVectorBucketName(), 
        listRequest.getVectorBucketArn()
    );
    
    return s3VectorsService.listIndexes(
        vectorBucketName,
        listRequest.getMaxResults(),
        listRequest.getNextToken(),
        listRequest.getPrefix()
    );
  }

}
