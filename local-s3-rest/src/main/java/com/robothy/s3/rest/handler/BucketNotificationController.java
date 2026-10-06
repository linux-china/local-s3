package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.service.BucketNotificationService;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.RequestUtils;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * See {@code docs/semantics.md#stored-not-applied}; the deprecated operations are answered by the same routes.
 */
class BucketNotificationController {

  private final BucketNotificationService notificationService;

  BucketNotificationController(ServiceFactory serviceFactory) {
    this.notificationService = serviceFactory.getInstance(BucketService.class);
  }

  void put(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    String configuration;
    try (InputStream in = RequestUtils.getBody(request).getDecodedBody()) {
      configuration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    notificationService.putBucketNotificationConfiguration(bucketName, configuration);
    ResponseUtils.addCommonHeaders(response)
        .status(HttpResponseStatus.OK);
  }

  void get(RouterHttpRequest request, RouterHttpResponse response) {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    String configuration = notificationService.getBucketNotificationConfiguration(bucketName);
    ResponseUtils.addCommonHeaders(response)
        .status(HttpResponseStatus.OK)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML)
        .write(configuration);
  }

}
