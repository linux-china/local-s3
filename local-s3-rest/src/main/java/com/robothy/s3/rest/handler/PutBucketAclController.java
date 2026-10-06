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
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * See {@code docs/semantics.md#access-control-lists} and {@linkplain AccessControlPolicyRequests}.
 */
class PutBucketAclController implements RouterHttpRequestHandler {

  private final BucketAclService aclService;

  private final XmlMapper xmlMapper;

  PutBucketAclController(ServiceFactory serviceFactory) {
    this.aclService = serviceFactory.getInstance(BucketService.class);
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);

    AccessControlPolicy acl = AccessControlPolicyRequests.read(request, xmlMapper,
        AccessControlPolicyRequests.Resource.BUCKET,
        () -> aclService.getBucketAcl(bucketName).getOwner(),
        () -> aclService.getBucketAcl(bucketName).getOwner());
    aclService.putBucketAcl(bucketName, acl);

    ResponseUtils.addDateHeader(response);
    ResponseUtils.addServerHeader(response);
    ResponseUtils.addAmzRequestId(response);
  }

}
