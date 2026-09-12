package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.model.answers.GetObjectAclAns;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObjectAcl.html">GetObjectAcl</a>.
 */
class GetObjectAclController extends ObjectHttpRequestHandler {

  GetObjectAclController(ServiceFactory serviceFactory) {
    super(serviceFactory);
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    String key = RequestAssertions.assertObjectKeyProvided(request);
    String versionId = request.parameter("versionId").orElse(null);
    GetObjectAclAns result = objectService.getObjectAcl(bucketName, key, versionId);

    ResponseUtils.addCommonHeaders(response)
        .status(HttpResponseStatus.OK)
        .write(xmlMapper.writeValueAsString(result.getAcl()));
    ResponseUtils.putHeaderIfPresent(response, AmzHeaderNames.X_AMZ_VERSION_ID, result.getVersionId());
  }

}
