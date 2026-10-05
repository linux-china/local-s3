package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.exception.InvalidCORSConfigurationException;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.datatypes.CORSConfiguration;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.RequestUtils;
import com.robothy.s3.rest.utils.ResponseUtils;
import java.io.InputStream;
import tools.jackson.core.JacksonException;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutBucketCors.html">PutBucketCors</a>.
 */
class PutBucketCorsController implements RouterHttpRequestHandler {

  private final BucketService bucketService;

  private final XmlMapper xmlMapper;

  PutBucketCorsController(ServiceFactory serviceFactory) {
    this.bucketService = serviceFactory.getInstance(BucketService.class);
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    CORSConfiguration configuration;
    try (InputStream in = RequestUtils.getBody(request).getDecodedBody()) {
      configuration = xmlMapper.readValue(in, CORSConfiguration.class);
    } catch (JacksonException _) {
      throw new InvalidCORSConfigurationException("The XML you provided was not well-formed or did not validate "
          + "against our published schema.");
    }
    bucketService.putBucketCors(bucketName, configuration);
    ResponseUtils.addCommonHeaders(response);
  }

}
