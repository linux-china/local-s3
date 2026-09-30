package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.datatypes.Tagging;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.RequestUtils;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.InputStream;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutBucketTagging.html">PutBucketTagging</a>
 */
class PutBucketTaggingController implements RouterHttpRequestHandler {

  private final BucketService bucketService;

  private final XmlMapper xmlMapper;

  PutBucketTaggingController(ServiceFactory serviceFactory) {
    this.bucketService = serviceFactory.getInstance(BucketService.class);
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);

    try(InputStream inputStream = RequestUtils.getBody(request).getDecodedBody()) {
      Tagging tagging = xmlMapper.readValue(inputStream, Tagging.class);
      bucketService.putTagging(bucketName, tagging.toCollection());
    }
    // Amazon S3 answers 204 No Content, like DeleteBucketTagging.
    response.status(HttpResponseStatus.NO_CONTENT);
    ResponseUtils.addCommonHeaders(response);
  }

}
