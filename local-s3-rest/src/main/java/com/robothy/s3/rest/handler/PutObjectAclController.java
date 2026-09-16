package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.service.BucketAclService;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.datatypes.AccessControlPolicy;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObjectAcl.html">PutObjectAcl</a>, whose ACL
 * is a canned ACL, grant headers or a document in the body; see {@linkplain AccessControlPolicyRequests}.
 * LocalS3 stores the ACL information without enforcing permissions.
 */
class PutObjectAclController extends ObjectHttpRequestHandler {

  private final BucketAclService bucketAclService;

  PutObjectAclController(ServiceFactory serviceFactory) {
    super(serviceFactory);
    this.bucketAclService = serviceFactory.getInstance(BucketService.class);
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    String key = RequestAssertions.assertObjectKeyProvided(request);
    String versionId = request.parameter("versionId").orElse(null);

    AccessControlPolicy acl = AccessControlPolicyRequests.read(request, xmlMapper,
        AccessControlPolicyRequests.Resource.OBJECT,
        () -> objectService.getObjectAcl(bucketName, key, versionId).getAcl().getOwner(),
        () -> bucketAclService.getBucketAcl(bucketName).getOwner());
    String returnedVersionId = objectService.putObjectAcl(bucketName, key, versionId, acl);

    ResponseUtils.addCommonHeaders(response)
        .status(HttpResponseStatus.OK);
    ResponseUtils.putHeaderIfPresent(response, AmzHeaderNames.X_AMZ_VERSION_ID, returnedVersionId);
  }

}
