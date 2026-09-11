package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteBucketCors.html">DeleteBucketCors</a>.
 */
class DeleteBucketCorsController implements HttpRequestHandler {

  private final BucketService bucketService;

  DeleteBucketCorsController(ServiceFactory serviceFactory) {
    this.bucketService = serviceFactory.getInstance(BucketService.class);
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    bucketService.deleteBucketCors(bucketName);
    response.status(HttpResponseStatus.NO_CONTENT);
    ResponseUtils.addCommonHeaders(response);
  }

}
