package com.robothy.s3.rest.handler.s3vectors;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.datatypes.s3vectors.VectorBucket;
import com.robothy.s3.datatypes.s3vectors.request.GetVectorBucketRequest;
import com.robothy.s3.datatypes.s3vectors.response.GetVectorBucketResponse;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.HttpRequestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Handle S3 Vectors GetVectorBucket operation.
 */
public class GetVectorBucketController implements RouterHttpRequestHandler {

  private static final Logger log = LoggerFactory.getLogger(GetVectorBucketController.class);

  private final S3VectorsService s3VectorsService;
  private final ObjectMapper objectMapper;

  public GetVectorBucketController(ServiceFactory serviceFactory) {
    this.s3VectorsService = serviceFactory.getInstance(S3VectorsService.class);
    this.objectMapper = serviceFactory.getInstance(ObjectMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    GetVectorBucketRequest getRequest = parseRequest(request);
    GetVectorBucketResponse getResponse = processRequest(getRequest);
    HttpRequestUtils.sendJsonResponse(response, getResponse, objectMapper);
  }

  private GetVectorBucketRequest parseRequest(RouterHttpRequest request) throws Exception {
    byte[] bodyBytes = HttpRequestUtils.extractRequestBody(request);
    return HttpRequestUtils.parseRequiredRequest(bodyBytes, GetVectorBucketRequest.class, objectMapper);
  }

  private GetVectorBucketResponse processRequest(GetVectorBucketRequest getRequest) {
    VectorBucket vectorBucket = s3VectorsService.getVectorBucket(getRequest.getVectorBucketName());
    return new GetVectorBucketResponse(vectorBucket);
  }

}
