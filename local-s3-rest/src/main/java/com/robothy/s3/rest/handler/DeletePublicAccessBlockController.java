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
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeletePublicAccessBlock.html">DeletePublicAccessBlock</a>.
 * Removes the PublicAccessBlock configuration from a bucket.
 */
class DeletePublicAccessBlockController implements RouterHttpRequestHandler {

  private final BucketService bucketService;

  DeletePublicAccessBlockController(ServiceFactory serviceFactory) {
    this.bucketService = serviceFactory.getInstance(BucketService.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    bucketService.deletePublicAccessBlock(bucketName);
    response.status(HttpResponseStatus.NO_CONTENT);

    ResponseUtils.addDateHeader(response);
    ResponseUtils.addServerHeader(response);
    ResponseUtils.addAmzRequestId(response);
  }
}
