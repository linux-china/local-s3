package com.robothy.s3.rest.handler;


import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.service.BucketPolicyService;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import com.robothy.s3.rest.netty.RequestBodies;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

class BucketPolicyController {

  private final BucketPolicyService bucketPolicyService;

  BucketPolicyController(ServiceFactory serviceFactory) {
    this.bucketPolicyService = serviceFactory.getInstance(BucketService.class);
  }

  void get(RouterHttpRequest request, RouterHttpResponse response) {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    String bucketPolicy = bucketPolicyService.getBucketPolicy(bucketName);
    response.status(HttpResponseStatus.OK)
        .write(bucketPolicy);
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addServerHeader(response);
    ResponseUtils.addAmzRequestId(response);
  }

  void put(RouterHttpRequest request, RouterHttpResponse response) throws IOException {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    try(InputStream in = RequestBodies.inputStream(request.getBody()) ){
      bucketPolicyService.putBucketPolicy(bucketName, new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addServerHeader(response);
    ResponseUtils.addAmzRequestId(response);
  }

  void delete(RouterHttpRequest httpRequest, RouterHttpResponse httpResponse) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(httpRequest);
    bucketPolicyService.deleteBucketPolicy(bucketName);
    httpResponse.status(HttpResponseStatus.OK);
    ResponseUtils.addDateHeader(httpResponse);
    ResponseUtils.addServerHeader(httpResponse);
    ResponseUtils.addAmzRequestId(httpResponse);
  }

}
