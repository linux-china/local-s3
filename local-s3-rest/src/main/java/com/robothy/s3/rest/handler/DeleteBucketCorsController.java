package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteBucketCors.html">DeleteBucketCors</a>.
 */
class DeleteBucketCorsController implements RouterHttpRequestHandler {

  private final BucketService bucketService;

  DeleteBucketCorsController(ServiceFactory serviceFactory) {
    this.bucketService = serviceFactory.getInstance(BucketService.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    bucketService.deleteBucketCors(bucketName);
    response.status(HttpResponseStatus.NO_CONTENT);
    ResponseUtils.addCommonHeaders(response);
  }

}
