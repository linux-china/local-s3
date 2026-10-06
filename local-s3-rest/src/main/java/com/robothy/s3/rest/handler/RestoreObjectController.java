package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.service.RestoreObjectService;
import com.robothy.s3.core.util.XmlConfigurations;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.RequestUtils;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * See {@code docs/semantics.md#storage-classes-and-restores}.
 */
class RestoreObjectController implements RouterHttpRequestHandler {

  private final RestoreObjectService restoreObjectService;

  RestoreObjectController(ServiceFactory serviceFactory) {
    this.restoreObjectService = serviceFactory.getInstance(ObjectService.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    String key = RequestAssertions.assertObjectKeyProvided(request);
    String restoreRequest;
    try (InputStream in = RequestUtils.getBody(request).getDecodedBody()) {
      restoreRequest = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    XmlConfigurations.assertWellFormed(restoreRequest, "RestoreRequest");
    int days = XmlConfigurations.childText(restoreRequest, "Days")
        .map(RestoreObjectController::parseDays)
        .orElseThrow(() -> new LocalS3RequestException(S3ErrorCode.MalformedXML));
    boolean restored = restoreObjectService.restoreObject(bucketName, key,
        request.parameter("versionId").orElse(null), days);
    ResponseUtils.addCommonHeaders(response)
        .status(restored ? HttpResponseStatus.OK : HttpResponseStatus.ACCEPTED);
  }

  private static int parseDays(String days) {
    try {
      return Integer.parseInt(days);
    } catch (NumberFormatException _) {
      throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
    }
  }

  static void addRestoreHeader(RouterHttpResponse response, Long restoreExpiryDate) {
    if (restoreExpiryDate != null) {
      response.putHeader(AmzHeaderNames.X_AMZ_RESTORE, "ongoing-request=\"false\", expiry-date=\""
          + ResponseUtils.toRfc1123DateTime(restoreExpiryDate) + "\"");
    }
  }

}
