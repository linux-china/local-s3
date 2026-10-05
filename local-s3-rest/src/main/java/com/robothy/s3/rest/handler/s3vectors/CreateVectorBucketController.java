package com.robothy.s3.rest.handler.s3vectors;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.datatypes.s3vectors.request.CreateVectorBucketRequest;
import com.robothy.s3.datatypes.s3vectors.response.CreateVectorBucketResponse;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.HttpRequestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

/**
 * Handle S3 Vectors CreateVectorBucket operation.
 */
public class CreateVectorBucketController implements RouterHttpRequestHandler {

  private static final Logger log = LoggerFactory.getLogger(CreateVectorBucketController.class);

  private final S3VectorsService s3VectorsService;
  private final ObjectMapper objectMapper;

  public CreateVectorBucketController(ServiceFactory serviceFactory) {
    this.s3VectorsService = serviceFactory.getInstance(S3VectorsService.class);
    this.objectMapper = serviceFactory.getInstance(ObjectMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    CreateVectorBucketRequest createRequest = parseRequest(request);
    CreateVectorBucketResponse createResponse = processRequest(createRequest);
    HttpRequestUtils.sendJsonResponse(response, createResponse, objectMapper);
  }

  private CreateVectorBucketRequest parseRequest(RouterHttpRequest request) throws Exception {
    byte[] bodyBytes = HttpRequestUtils.extractRequestBody(request);
    return HttpRequestUtils.parseRequiredRequest(bodyBytes, CreateVectorBucketRequest.class, objectMapper);
  }

  private CreateVectorBucketResponse processRequest(CreateVectorBucketRequest createRequest) {
    return s3VectorsService.createVectorBucket(
        createRequest.getVectorBucketName(),
        createRequest.getEncryptionConfiguration(),
        createRequest.getTags()
    );
  }

}
