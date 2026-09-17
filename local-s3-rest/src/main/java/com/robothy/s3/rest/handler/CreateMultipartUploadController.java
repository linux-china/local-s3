package com.robothy.s3.rest.handler;

import com.robothy.s3.core.model.internal.CustomerEncryption;
import com.robothy.s3.rest.utils.ChecksumHeaders;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.core.util.Checksums;
import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import com.robothy.s3.datatypes.enums.ChecksumType;
import com.robothy.s3.rest.utils.CustomerEncryptionHeaders;
import com.robothy.s3.rest.utils.ObjectLockHeaders;
import java.util.Objects;
import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.model.request.CreateMultipartUploadOptions;
import com.robothy.s3.core.service.CreateMultipartUploadService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.model.response.InitiateMultipartUploadResult;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.RequestUtils;
import com.robothy.s3.rest.utils.ResponseUtils;
import com.robothy.s3.rest.utils.SystemMetadataHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CreateMultipartUpload.html">CreateMultipartUpload</a>
 */
class CreateMultipartUploadController implements HttpRequestHandler {

  private final CreateMultipartUploadService uploadService;

  private final XmlMapper xmlMapper;

  CreateMultipartUploadController(ServiceFactory serviceFactory) {
    this.uploadService = serviceFactory.getInstance(ObjectService.class);
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
    String bucket = RequestAssertions.assertBucketNameProvided(request);
    String key = RequestAssertions.assertObjectKeyProvided(request);
    String contentType = request.header("content-type").orElse("octet/stream");
    CheckSumAlgorithm checksumAlgorithm = ChecksumHeaders.algorithm(request, AmzHeaderNames.X_AMZ_CHECKSUM_ALGORITHM);
    ChecksumType checksumType = ChecksumHeaders.type(request);
    CustomerEncryption customerEncryption = CustomerEncryptionHeaders.fromRequest(request);
    String uploadId = uploadService.createMultipartUpload(bucket, key, CreateMultipartUploadOptions.builder()
        .tagging(RequestUtils.extractTagging(request).orElse(null))
        .userMetadata(RequestUtils.extractUserMetadata(request))
        .contentType(contentType)
        .systemMetadata(SystemMetadataHeaders.fromRequest(request))
        .checksumAlgorithm(checksumAlgorithm)
        .checksumType(checksumType)
        .objectLock(ObjectLockHeaders.fromRequest(request))
        .customerEncryption(customerEncryption)
        .build());
    InitiateMultipartUploadResult result = InitiateMultipartUploadResult.builder()
        .bucket(bucket)
        .key(key)
        .uploadId(uploadId)
        .build();
    response.status(HttpResponseStatus.OK)
        .write(xmlMapper.writeValueAsString(result));
    if (Objects.nonNull(checksumAlgorithm)) {
      response.putHeader(AmzHeaderNames.X_AMZ_CHECKSUM_ALGORITHM, checksumAlgorithm)
          .putHeader(AmzHeaderNames.X_AMZ_CHECKSUM_TYPE,
              Objects.requireNonNullElseGet(checksumType, () -> Checksums.defaultMultipartType(checksumAlgorithm)));
    }
    CustomerEncryptionHeaders.addHeaders(response, customerEncryption);
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addServerHeader(response);
    ResponseUtils.addAmzRequestId(response);
  }

}
