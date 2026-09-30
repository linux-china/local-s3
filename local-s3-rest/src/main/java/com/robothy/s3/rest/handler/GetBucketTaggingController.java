package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.datatypes.Tagging;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Collection;
import java.util.Map;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetBucketTagging.html">GetBucketTagging<a/>
 */
class GetBucketTaggingController implements RouterHttpRequestHandler {

  private final BucketService bucketService;

  private final XmlMapper xmlMapper;

  GetBucketTaggingController(ServiceFactory serviceFactory) {
    this.bucketService = serviceFactory.getInstance(BucketService.class);
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {

    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    Collection<Map<String, String>> tagSets = bucketService.getTagging(bucketName);
    Tagging tagging = Tagging.fromCollection(tagSets);
    String responseBody = xmlMapper.writeValueAsString(tagging);
    response.write(responseBody);
    response.status(HttpResponseStatus.OK);
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addServerHeader(response);
  }

}
