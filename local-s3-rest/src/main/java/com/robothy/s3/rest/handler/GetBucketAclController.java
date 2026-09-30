package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.service.BucketAclService;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.datatypes.AccessControlPolicy;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpResponseStatus;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetBucketAcl.html">GetBucketAcl</a>.
 * Get access control of a specified bucket.
 */
class GetBucketAclController implements RouterHttpRequestHandler {

  private final BucketAclService aclService;

  private final XmlMapper xmlMapper;

  GetBucketAclController(ServiceFactory serviceFactory) {
    this.aclService = serviceFactory.getInstance(BucketService.class);
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    AccessControlPolicy acl = aclService.getBucketAcl(bucketName);
    response.status(HttpResponseStatus.OK)
        .write(xmlMapper.writeValueAsString(acl));
    ResponseUtils.addAmzRequestId(response);
    ResponseUtils.addServerHeader(response);
    ResponseUtils.addDateHeader(response);
  }

}
