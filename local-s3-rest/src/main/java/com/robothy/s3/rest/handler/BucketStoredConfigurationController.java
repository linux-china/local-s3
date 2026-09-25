package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.s3.core.model.IdentifiedBucketConfiguration;
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
import java.util.regex.Pattern;

/**
 * Handles the configurations of a bucket that LocalS3 stores and returns but never applies, e.g.
 * {@code PutBucketWebsite}, {@code GetBucketOwnershipControls} or {@code GetBucketLogging}; see
 * {@linkplain StoredBucketConfiguration}, and the ones of which a bucket has several, each named by the {@code id}
 * parameter, e.g. {@code PutBucketMetricsConfiguration}; see {@linkplain IdentifiedBucketConfiguration}. One controller
 * answers the operations of every such configuration, each route naming the configuration it addresses.
 */
class BucketStoredConfigurationController {

  /**
   * The XML declaration of a stored document, which a document nested in a list must not carry.
   */
  private static final Pattern XML_DECLARATION = Pattern.compile("^\\s*<\\?xml[^>]*\\?>\\s*");

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

  /**
   * The {@code PUT} of a configuration named by the {@code id} parameter, which stores the document as it was sent.
   */
  HttpRequestHandler put(IdentifiedBucketConfiguration type) {
    return (request, response) -> {
      String bucketName = RequestAssertions.assertBucketNameProvided(request);
      String configuration;
      try (InputStream in = RequestUtils.getBody(request).getDecodedBody()) {
        configuration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      configurationService.putBucketConfiguration(bucketName, type, request.parameter("id").orElse(null),
          configuration);
      ResponseUtils.addCommonHeaders(response)
          .status(HttpResponseStatus.OK);
    };
  }

  /**
   * The {@code GET} of a configuration named by the {@code id} parameter.
   */
  HttpRequestHandler get(IdentifiedBucketConfiguration type) {
    return (request, response) -> {
      String bucketName = RequestAssertions.assertBucketNameProvided(request);
      String configuration = configurationService.getBucketConfiguration(bucketName, type,
          request.parameter("id").orElse(null));
      ResponseUtils.addCommonHeaders(response)
          .status(HttpResponseStatus.OK)
          .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML)
          .write(configuration);
    };
  }

  /**
   * The {@code GET} of the configurations of a kind, without the {@code id} parameter. Every configuration is on the
   * one page, so the list is never truncated.
   */
  HttpRequestHandler list(IdentifiedBucketConfiguration type) {
    return (request, response) -> {
      String bucketName = RequestAssertions.assertBucketNameProvided(request);
      StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
          .append('<').append(type.listRootElement()).append(" xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">")
          .append("<IsTruncated>false</IsTruncated>");
      for (String configuration : configurationService.listBucketConfigurations(bucketName, type)) {
        xml.append(XML_DECLARATION.matcher(configuration).replaceFirst(""));
      }
      xml.append("</").append(type.listRootElement()).append('>');
      ResponseUtils.addCommonHeaders(response)
          .status(HttpResponseStatus.OK)
          .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML)
          .write(xml.toString());
    };
  }

  /**
   * The {@code DELETE} of a configuration named by the {@code id} parameter.
   */
  HttpRequestHandler delete(IdentifiedBucketConfiguration type) {
    return (request, response) -> {
      String bucketName = RequestAssertions.assertBucketNameProvided(request);
      configurationService.deleteBucketConfiguration(bucketName, type, request.parameter("id").orElse(null));
      ResponseUtils.addCommonHeaders(response)
          .status(HttpResponseStatus.NO_CONTENT);
    };
  }

}
