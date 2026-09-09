package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.datatypes.AccessControlPolicy;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.InputStream;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObjectAcl.html">PutObjectAcl</a>.
 * LocalS3 stores the ACL information without enforcing permissions.
 */
class PutObjectAclController extends ObjectHttpRequestHandler {

  PutObjectAclController(ServiceFactory serviceFactory) {
    super(serviceFactory);
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    String key = RequestAssertions.assertObjectKeyProvided(request);
    String versionId = request.parameter("versionId").orElse(null);

    String returnedVersionId;
    try (InputStream in = new ByteBufInputStream(request.getBody())) {
      AccessControlPolicy acl = xmlMapper.readValue(in, AccessControlPolicy.class);
      returnedVersionId = objectService.putObjectAcl(bucketName, key, versionId, acl);
    }

    ResponseUtils.addCommonHeaders(response)
        .status(HttpResponseStatus.OK)
        .putHeader(AmzHeaderNames.X_AMZ_VERSION_ID, returnedVersionId);
  }

}
