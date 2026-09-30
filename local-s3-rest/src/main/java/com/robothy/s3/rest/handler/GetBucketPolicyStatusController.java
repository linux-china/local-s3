package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.datatypes.PolicyStatus;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpResponseStatus;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetBucketPolicyStatus.html">GetBucketPolicyStatus</a>.
 * Returns the policy status for a specified bucket.
 */
class GetBucketPolicyStatusController implements RouterHttpRequestHandler {

  private final BucketService bucketService;

  private final XmlMapper xmlMapper;

  GetBucketPolicyStatusController(ServiceFactory serviceFactory) {
    this.bucketService = serviceFactory.getInstance(BucketService.class);
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);    
    PolicyStatus policyStatus;
    try {
      policyStatus = bucketService.getBucketPolicyStatus(bucketName);
    } catch (BucketNotExistException e) {
      response.status(HttpResponseStatus.NOT_FOUND);
      return;
    }    response.write(xmlMapper.writeValueAsString(policyStatus));
    ResponseUtils.addCommonHeaders(response);
  }
}
