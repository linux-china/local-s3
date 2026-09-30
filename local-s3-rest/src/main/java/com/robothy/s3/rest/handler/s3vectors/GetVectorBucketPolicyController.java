package com.robothy.s3.rest.handler.s3vectors;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.core.util.S3VectorsArnUtils;
import com.robothy.s3.datatypes.s3vectors.request.GetVectorBucketPolicyRequest;
import com.robothy.s3.datatypes.s3vectors.response.GetVectorBucketPolicyResponse;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.HttpRequestUtils;
import tools.jackson.databind.ObjectMapper;

public class GetVectorBucketPolicyController implements RouterHttpRequestHandler {

  private final S3VectorsService s3VectorsService;
  private final ObjectMapper objectMapper;

  public GetVectorBucketPolicyController(ServiceFactory serviceFactory) {
    this.s3VectorsService = serviceFactory.getInstance(S3VectorsService.class);
    this.objectMapper = serviceFactory.getInstance(ObjectMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    byte[] bodyBytes = HttpRequestUtils.extractRequestBody(request);
    GetVectorBucketPolicyRequest getRequest =
        HttpRequestUtils.parseRequiredRequest(bodyBytes, GetVectorBucketPolicyRequest.class, objectMapper);

    String vectorBucketName = S3VectorsArnUtils.resolveBucketName(
        getRequest.getVectorBucketName(),
        getRequest.getVectorBucketArn()
    );
    GetVectorBucketPolicyResponse getResponse = s3VectorsService.getVectorBucketPolicy(vectorBucketName);

    HttpRequestUtils.sendJsonResponse(response, getResponse, objectMapper);
  }
}
