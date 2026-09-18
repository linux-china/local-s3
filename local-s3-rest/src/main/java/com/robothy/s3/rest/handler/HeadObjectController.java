package com.robothy.s3.rest.handler;

import com.robothy.s3.rest.utils.ChecksumHeaders;
import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.model.answers.GetObjectAns;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.service.GetObjectService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.model.request.Range;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.CustomerEncryptionHeaders;
import com.robothy.s3.rest.utils.ObjectLockHeaders;
import com.robothy.s3.rest.utils.RequestUtils;
import com.robothy.s3.rest.utils.ResponseUtils;
import com.robothy.s3.rest.utils.ServerSideEncryptionHeaders;
import com.robothy.s3.rest.utils.SystemMetadataHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_HeadObject.html">HeadObject</a>
 */
class HeadObjectController implements HttpRequestHandler {

  private final GetObjectService objectService;

  HeadObjectController(ServiceFactory serviceFactory) {
    this.objectService = serviceFactory.getInstance(ObjectService.class);
  }


  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
    String bucket = RequestAssertions.assertBucketNameProvided(request);
    String key = RequestAssertions.assertObjectKeyProvided(request);

    GetObjectOptions options = GetObjectOptions.builder()
        .versionId(request.parameter("versionId").orElse(null))
        .range(request.header(HttpHeaderNames.RANGE.toString()).map(Range::parse).orElse(null))
        .partNumber(RequestAssertions.assertPartNumberIsValidIfPresent(request))
        .preconditions(RequestUtils.extractPreconditions(request))
        .customerEncryption(CustomerEncryptionHeaders.fromRequest(request))
        .build();
    GetObjectAns object = objectService.headObject(bucket, key, options);

    // Answered only for a delete marker, like Amazon S3 does, and like GetObjectController answers it.
    if (object.isDeleteMarker()) {
      response.putHeader(AmzHeaderNames.X_AMZ_DELETE_MARKER, true);
    }
    response.putHeader(HttpHeaderNames.LAST_MODIFIED.toString(),
        ResponseUtils.toRfc1123DateTime(object.getLastModified()));
    ResponseUtils.putHeaderIfPresent(response, AmzHeaderNames.X_AMZ_VERSION_ID, object.getVersionId());

    if (object.isNotModified()) {
      // The client already holds this version. A 304 carries none of the headers that describe content;
      // the ETag and the Last-Modified above identify the version that it holds.
      response.status(HttpResponseStatus.NOT_MODIFIED);
      ResponseUtils.addETag(response, object.getEtag());
    } else if (!object.isDeleteMarker()) {
      if (object.getContentRange() != null) {
        response.status(HttpResponseStatus.PARTIAL_CONTENT)
            .putHeader(HttpHeaderNames.CONTENT_RANGE.toString(), object.getContentRange());
      } else {
        response.status(HttpResponseStatus.OK);
      }

      response.putHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), object.getSize())
          .putHeader(HttpHeaderNames.ETAG.toString(), object.getEtag())
          .putHeader("Accept-Ranges", "bytes");
      ResponseUtils.putHeaderIfPresent(response, AmzHeaderNames.X_AMZ_MP_PARTS_COUNT, object.getPartsCount());
      SystemMetadataHeaders.addResponseHeaders(request, response, object.getContentType(), object.getSystemMetadata());
      ResponseUtils.addETag(response, object.getEtag());
      object.getUserMetadata().forEach((k, v) -> response.putHeader(AmzHeaderNames.X_AMZ_META_PREFIX + k, v));
      ObjectLockHeaders.addHeaders(response, object.getObjectLock());
      CustomerEncryptionHeaders.addHeaders(response, object.getCustomerEncryption());
      ServerSideEncryptionHeaders.addHeaders(response, object.getServerSideEncryption(), false);
      if (ChecksumHeaders.isChecksumModeEnabled(request)) {
        ChecksumHeaders.addHeaders(response, object.getChecksum());
      }
    } else {
      response.status(HttpResponseStatus.METHOD_NOT_ALLOWED);
    }

    ResponseUtils.addServerHeader(response);
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addAmzRequestId(response);
  }

}
