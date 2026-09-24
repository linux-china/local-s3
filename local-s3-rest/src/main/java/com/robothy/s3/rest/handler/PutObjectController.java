package com.robothy.s3.rest.handler;

import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.internal.CustomerEncryption;
import com.robothy.s3.rest.utils.ChecksumHeaders;
import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.model.answers.PutObjectAns;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.model.request.DecodedAmzRequestBody;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.CustomerEncryptionHeaders;
import com.robothy.s3.rest.utils.ObjectLockHeaders;
import com.robothy.s3.rest.utils.RequestUtils;
import com.robothy.s3.rest.utils.ResponseUtils;
import com.robothy.s3.rest.utils.ServerSideEncryptionHeaders;
import com.robothy.s3.rest.utils.SystemMetadataHeaders;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Base64;
import java.util.Objects;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html">PutObject<a/>.
 */
class PutObjectController extends ObjectHttpRequestHandler {

  PutObjectController(ServiceFactory serviceFactory) {
    super(serviceFactory);
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    String key = RequestAssertions.assertObjectKeyProvided(request);

    DecodedAmzRequestBody decodedBody = RequestUtils.getBody(request);
    CustomerEncryption customerEncryption = CustomerEncryptionHeaders.fromRequest(request);
    Long writeOffsetBytes = writeOffsetBytes(request);

    PutObjectOptions options = PutObjectOptions.builder()
        .contentType(request.header(HttpHeaderNames.CONTENT_TYPE).orElse(null))
        .systemMetadata(SystemMetadataHeaders.fromRequest(request))
        .size(decodedBody.getDecodedContentLength())
        .content(decodedBody.getDecodedBody())
        .contentFile(decodedBody.getBodyFile())
        .contentMd5(contentMd5(request))
        .checksum(ChecksumHeaders.fromRequest(request, decodedBody))
        .tagging(RequestUtils.extractTagging(request).orElse(null))
        .userMetadata(RequestUtils.extractUserMetadata(request))
        .preconditions(RequestUtils.extractPreconditions(request))
        .objectLock(ObjectLockHeaders.fromRequest(request))
        .customerEncryption(customerEncryption)
        .serverSideEncryption(ServerSideEncryptionHeaders.fromRequest(request, customerEncryption))
        .writeOffsetBytes(writeOffsetBytes)
        .build();

    PutObjectAns ans = objectService.putObject(bucketName, key, options);
    response.status(HttpResponseStatus.OK)
        .putHeader(HttpHeaderNames.CONTENT_LENGTH.toString(), 0);

    if (Objects.nonNull(ans.getVersionId())) {
      response.putHeader(AmzHeaderNames.X_AMZ_VERSION_ID, ans.getVersionId());
    }

    ResponseUtils.addETag(response, ans.getEtag());
    ChecksumHeaders.addHeaders(response, ans.getChecksum());
    CustomerEncryptionHeaders.addHeaders(response, customerEncryption);
    // The encryption of the stored version, which is the one of the object that an append extends.
    ServerSideEncryptionHeaders.addHeaders(response, ans.getServerSideEncryption(), true);
    if (Objects.nonNull(writeOffsetBytes)) {
      response.putHeader(AmzHeaderNames.X_AMZ_OBJECT_SIZE, ans.getSize());
    }
    ResponseUtils.addServerHeader(response);
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addAmzRequestId(response);
  }

  /**
   * The {@code Content-MD5} of the request, which is checked against the content once it is stored.
   *
   * @return the header; {@code null} if the request has none.
   * @throws LocalS3RequestException {@code InvalidDigest} if the header isn't the base64 of an MD5 digest, e.g. is
   *     empty, like Amazon S3 rejects it before it reads the content; a digest that doesn't match is {@code BadDigest}.
   */
  private static String contentMd5(HttpRequest request) {
    String value = request.header(HttpHeaderNames.CONTENT_MD5).orElse(null);
    if (Objects.isNull(value)) {
      return null;
    }
    try {
      if (Base64.getDecoder().decode(value).length == 16) {
        return value;
      }
    } catch (IllegalArgumentException e) {
      // Rejected below.
    }
    throw new LocalS3RequestException(S3ErrorCode.InvalidDigest);
  }

  /**
   * The offset that the request appends its content at.
   *
   * @return the {@code x-amz-write-offset-bytes}; {@code null} if the request replaces the object.
   * @throws LocalS3InvalidArgumentException if the header isn't a number.
   */
  private static Long writeOffsetBytes(HttpRequest request) {
    String value = request.header(AmzHeaderNames.X_AMZ_WRITE_OFFSET_BYTES).orElse(null);
    if (Objects.isNull(value)) {
      return null;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException e) {
      throw new LocalS3InvalidArgumentException(AmzHeaderNames.X_AMZ_WRITE_OFFSET_BYTES, value,
          "The write offset must be a number of bytes.");
    }
  }

}
