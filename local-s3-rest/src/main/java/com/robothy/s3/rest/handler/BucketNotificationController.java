package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
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
 * Handles the notification configuration of a bucket, which LocalS3 stores and returns but never delivers to; see
 * {@linkplain BucketNotificationService}.
 *
 * <p>The deprecated {@code PutBucketNotification} and {@code GetBucketNotification} send the same requests as
 * {@code PutBucketNotificationConfiguration} and {@code GetBucketNotificationConfiguration}, and are answered by them.
 */
class BucketNotificationController {

  private final BucketNotificationService notificationService;

  BucketNotificationController(ServiceFactory serviceFactory) {
    this.notificationService = serviceFactory.getInstance(BucketService.class);
  }

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutBucketNotificationConfiguration.html">PutBucketNotificationConfiguration</a>
   */
  void put(HttpRequest request, HttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    String configuration;
    try (InputStream in = RequestUtils.getBody(request).getDecodedBody()) {
      configuration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    notificationService.putBucketNotificationConfiguration(bucketName, configuration);
    ResponseUtils.addCommonHeaders(response)
        .status(HttpResponseStatus.OK);
  }

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetBucketNotificationConfiguration.html">GetBucketNotificationConfiguration</a>
   */
  void get(HttpRequest request, HttpResponse response) {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    String configuration = notificationService.getBucketNotificationConfiguration(bucketName);
    ResponseUtils.addCommonHeaders(response)
        .status(HttpResponseStatus.OK)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML)
        .write(configuration);
  }

}
