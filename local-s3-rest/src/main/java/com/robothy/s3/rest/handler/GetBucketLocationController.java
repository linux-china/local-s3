package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.constants.ServiceConstants;
import com.robothy.s3.core.model.Bucket;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.datatypes.response.LocationConstraint;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import tools.jackson.dataformat.xml.XmlMapper;

class GetBucketLocationController implements RouterHttpRequestHandler {

  private final XmlMapper xmlMapper;

  private final BucketService bucketService;

  public GetBucketLocationController(ServiceFactory serviceFactory) {
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
    this.bucketService = serviceFactory.getInstance(BucketService.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    Bucket bucket = bucketService.getBucket(bucketName);
    String region = bucket.regionOrDefault();
    // Amazon S3 answers an empty location constraint for the buckets in us-east-1.
    LocationConstraint locationConstraint =
        new LocationConstraint(ServiceConstants.DEFAULT_REGION.equals(region) ? "" : region);
    response.write(xmlMapper.writeValueAsString(locationConstraint));
    ResponseUtils.addCommonHeaders(response);
  }

}
