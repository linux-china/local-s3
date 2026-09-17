package com.robothy.s3.rest.handler;

import com.robothy.s3.rest.utils.ChecksumHeaders;
import com.robothy.s3.rest.model.response.ChecksumElements;
import com.robothy.s3.core.model.request.RequestChecksum;
import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import com.robothy.s3.datatypes.enums.ChecksumType;
import com.robothy.s3.rest.model.request.CompletedPart;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.model.answers.CompleteMultipartUploadAns;
import com.robothy.s3.core.model.request.CompleteMultipartUploadPartOption;
import com.robothy.s3.core.service.CompleteMultipartUploadService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.model.request.CompleteMultipartUpload;
import com.robothy.s3.rest.model.response.CompleteMultipartUploadResult;
import com.robothy.s3.rest.service.MultipartUploadPolicy;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.RequestUtils;
import com.robothy.s3.rest.utils.ResponseUtils;
import com.robothy.s3.rest.netty.RequestBodies;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.InputStream;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CompleteMultipartUpload.html">CompleteMultipartUpload</a>
 */
class CompleteMultipartUploadController extends ObjectHttpRequestHandler {

  private final CompleteMultipartUploadService uploadService;

  private final MultipartUploadPolicy multipartUploadPolicy;

  CompleteMultipartUploadController(ServiceFactory serviceFactory) {
    super(serviceFactory);
    this.uploadService = serviceFactory.getInstance(ObjectService.class);
    // A router built from a bare service factory, e.g. in a test, applies the default policy.
    this.multipartUploadPolicy = serviceFactory.containsInstance(MultipartUploadPolicy.class)
        ? serviceFactory.getInstance(MultipartUploadPolicy.class)
        : MultipartUploadPolicy.of(true);
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
    String bucket = RequestAssertions.assertBucketNameProvided(request);
    String key = RequestAssertions.assertObjectKeyProvided(request);
    String uploadId = RequestAssertions.assertUploadIdIsProvided(request);

    RequestChecksum expectedChecksum = ChecksumHeaders.fromHeaders(request);
    ChecksumType expectedChecksumType = ChecksumHeaders.type(request);
    CompleteMultipartUploadAns completeMultipartUploadAns;
    try(InputStream in = RequestBodies.inputStream(request.getBody())) {
      CompleteMultipartUpload completeMultipartUpload = xmlMapper.readValue(in, CompleteMultipartUpload.class);
      List<CompleteMultipartUploadPartOption> parts = completeMultipartUpload.getParts().stream().map(part -> CompleteMultipartUploadPartOption.builder()
                  .etag(part.getEtag())
                  .partNumber(part.getPartNumber())
                  .checksums(partChecksums(part))
                  .build())
              .collect(Collectors.toList());
      completeMultipartUploadAns = uploadService.completeMultipartUpload(bucket, key, uploadId, parts,
          multipartUploadPolicy.minimumPartSize(), multipartUploadPolicy.compositeEtags(),
          RequestUtils.extractPreconditions(request), expectedChecksum, expectedChecksumType);
    }

    CompleteMultipartUploadResult result = CompleteMultipartUploadResult.builder()
        .bucket(bucket)
        .key(key)
        .etag(ResponseUtils.quoteEtag(completeMultipartUploadAns.getEtag()))
        .location(completeMultipartUploadAns.getLocation())
        .checksum(ChecksumElements.of(completeMultipartUploadAns.getChecksum()))
        .build();
    response.status(HttpResponseStatus.OK)
        .write(xmlMapper.writeValueAsString(result));
    ResponseUtils.putHeaderIfPresent(response, AmzHeaderNames.X_AMZ_VERSION_ID,
        completeMultipartUploadAns.getVersionId());

    ResponseUtils.addDateHeader(response);
    ResponseUtils.addAmzRequestId(response);
    ResponseUtils.addServerHeader(response);
  }

  /**
   * The checksums that the request names a part with, by algorithm.
   */
  private static Map<CheckSumAlgorithm, String> partChecksums(CompletedPart part) {
    Map<CheckSumAlgorithm, String> checksums = new EnumMap<>(CheckSumAlgorithm.class);
    putIfPresent(checksums, CheckSumAlgorithm.CRC32, part.getChecksumCRC32());
    putIfPresent(checksums, CheckSumAlgorithm.CRC32C, part.getChecksumCRC32C());
    putIfPresent(checksums, CheckSumAlgorithm.CRC64NVME, part.getChecksumCRC64NVME());
    putIfPresent(checksums, CheckSumAlgorithm.SHA1, part.getChecksumSHA1());
    putIfPresent(checksums, CheckSumAlgorithm.SHA256, part.getChecksumSHA256());
    return checksums;
  }

  private static void putIfPresent(Map<CheckSumAlgorithm, String> checksums, CheckSumAlgorithm algorithm,
                                   String value) {
    if (Objects.nonNull(value) && !value.isBlank()) {
      checksums.put(algorithm, value);
    }
  }

}
