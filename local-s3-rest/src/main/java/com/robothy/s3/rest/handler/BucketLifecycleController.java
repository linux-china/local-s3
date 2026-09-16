package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.NoSuchLifecycleConfigurationException;
import com.robothy.s3.core.model.BucketLifecycleConfiguration;
import com.robothy.s3.core.service.BucketLifecycleService;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.RequestUtils;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Handles the lifecycle configuration of a bucket, which LocalS3 stores and returns but never applies; see
 * {@linkplain BucketLifecycleService}.
 *
 * <p>The deprecated {@code PutBucketLifecycle} and {@code GetBucketLifecycle} send the same requests as
 * {@code PutBucketLifecycleConfiguration} and {@code GetBucketLifecycleConfiguration}, and are answered by them.
 */
class BucketLifecycleController {

  private final BucketLifecycleService lifecycleService;

  BucketLifecycleController(ServiceFactory serviceFactory) {
    this.lifecycleService = serviceFactory.getInstance(BucketService.class);
  }

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutBucketLifecycleConfiguration.html">PutBucketLifecycleConfiguration</a>
   */
  void put(HttpRequest request, HttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    String configuration;
    try (InputStream in = RequestUtils.getBody(request).getDecodedBody()) {
      configuration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    String minimumObjectSize = request.header(AmzHeaderNames.X_AMZ_TRANSITION_DEFAULT_MINIMUM_OBJECT_SIZE)
        .orElse(null);
    BucketLifecycleConfiguration lifecycle =
        lifecycleService.putBucketLifecycleConfiguration(bucketName, configuration, minimumObjectSize);
    ResponseUtils.addCommonHeaders(response)
        .status(HttpResponseStatus.OK)
        .putHeader(AmzHeaderNames.X_AMZ_TRANSITION_DEFAULT_MINIMUM_OBJECT_SIZE,
            lifecycle.transitionDefaultMinimumObjectSize());
  }

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetBucketLifecycleConfiguration.html">GetBucketLifecycleConfiguration</a>
   */
  void get(HttpRequest request, HttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    BucketLifecycleConfiguration lifecycle = lifecycleService.getBucketLifecycleConfiguration(bucketName)
        .orElseThrow(() -> new NoSuchLifecycleConfigurationException(bucketName));
    ResponseUtils.addCommonHeaders(response)
        .status(HttpResponseStatus.OK)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML)
        .putHeader(AmzHeaderNames.X_AMZ_TRANSITION_DEFAULT_MINIMUM_OBJECT_SIZE,
            lifecycle.transitionDefaultMinimumObjectSize())
        .write(lifecycle.configuration());
  }

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteBucketLifecycle.html">DeleteBucketLifecycle</a>
   */
  void delete(HttpRequest request, HttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    lifecycleService.deleteBucketLifecycle(bucketName);
    ResponseUtils.addCommonHeaders(response)
        .status(HttpResponseStatus.NO_CONTENT);
  }

}
