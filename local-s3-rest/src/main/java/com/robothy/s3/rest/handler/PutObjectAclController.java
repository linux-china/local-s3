package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.service.BucketAclService;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.datatypes.AccessControlPolicy;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * See {@code docs/semantics.md#access-control-lists} and {@linkplain AccessControlPolicyRequests}.
 */
class PutObjectAclController extends ObjectHttpRequestHandler {

  private final BucketAclService bucketAclService;

  PutObjectAclController(ServiceFactory serviceFactory) {
    super(serviceFactory);
    this.bucketAclService = serviceFactory.getInstance(BucketService.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
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
