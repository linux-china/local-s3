package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.service.BucketAclService;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.datatypes.AccessControlPolicy;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutBucketAcl.html">PutBucketAcl</a>, whose ACL
 * is a canned ACL, grant headers or a document in the body; see {@linkplain AccessControlPolicyRequests}.
 * LocalS3 only stores the Acl information for the specified bucket;
 * it doesn't do granting actions.
 */
class PutBucketAclController implements HttpRequestHandler {

  private final BucketAclService aclService;

  private final XmlMapper xmlMapper;

  PutBucketAclController(ServiceFactory serviceFactory) {
    this.aclService = serviceFactory.getInstance(BucketService.class);
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
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
