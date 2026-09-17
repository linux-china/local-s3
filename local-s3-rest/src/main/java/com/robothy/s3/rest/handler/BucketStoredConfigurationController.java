package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.s3.core.model.StoredBucketConfiguration;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.BucketStoredConfigurationService;
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
 * Handles the configurations of a bucket that LocalS3 stores and returns but never applies, e.g.
 * {@code PutBucketWebsite}, {@code GetBucketOwnershipControls} or {@code GetBucketLogging}; see
 * {@linkplain StoredBucketConfiguration}. One controller answers the operations of every such configuration, each
 * route naming the configuration it addresses.
 */
class BucketStoredConfigurationController {

  private final BucketStoredConfigurationService configurationService;

  BucketStoredConfigurationController(ServiceFactory serviceFactory) {
    this.configurationService = serviceFactory.getInstance(BucketService.class);
  }

  /**
   * The {@code PUT} of a configuration, which stores the document as it was sent.
   */
  HttpRequestHandler put(StoredBucketConfiguration type) {
    return (request, response) -> {
      String bucketName = RequestAssertions.assertBucketNameProvided(request);
      String configuration;
      try (InputStream in = RequestUtils.getBody(request).getDecodedBody()) {
        configuration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      configurationService.putBucketConfiguration(bucketName, type, configuration);
      ResponseUtils.addCommonHeaders(response)
          .status(HttpResponseStatus.OK);
    };
  }

  /**
   * The {@code GET} of a configuration.
   */
  HttpRequestHandler get(StoredBucketConfiguration type) {
    return (request, response) -> {
      String bucketName = RequestAssertions.assertBucketNameProvided(request);
      String configuration = configurationService.getBucketConfiguration(bucketName, type);
      ResponseUtils.addCommonHeaders(response)
          .status(HttpResponseStatus.OK)
          .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML)
          .write(configuration);
    };
  }

  /**
   * The {@code DELETE} of a configuration.
   */
  HttpRequestHandler delete(StoredBucketConfiguration type) {
    return (request, response) -> {
      String bucketName = RequestAssertions.assertBucketNameProvided(request);
      configurationService.deleteBucketConfiguration(bucketName, type);
      ResponseUtils.addCommonHeaders(response)
          .status(HttpResponseStatus.NO_CONTENT);
    };
  }

}
