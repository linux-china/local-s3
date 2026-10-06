package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.model.Bucket;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.datatypes.response.GetBucketResult;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.service.ServiceFactory;
import java.time.Instant;

import tools.jackson.dataformat.xml.XmlMapper;

class GetBucketController implements RouterHttpRequestHandler {

  private final BucketService bucketService;

  private final XmlMapper xmlMapper;

  GetBucketController(ServiceFactory serviceFactory) {
    this.bucketService = serviceFactory.getInstance(BucketService.class);
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    Bucket bucket = bucketService.getBucket(bucketName);
    GetBucketResult getBucketResult =
        new GetBucketResult(bucket.getName(), false, Instant.ofEpochMilli(bucket.getCreationDate()));
    response.write(xmlMapper.writeValueAsString(getBucketResult));
  }

}
