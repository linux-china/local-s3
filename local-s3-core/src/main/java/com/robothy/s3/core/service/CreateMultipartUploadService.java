package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.ObjectAssertions;
import com.robothy.s3.core.assertions.ObjectLockAssertions;
import com.robothy.s3.core.model.answers.CreateMultipartUploadAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ServerSideEncryption;
import com.robothy.s3.core.model.internal.UploadMetadata;
import com.robothy.s3.core.model.request.CreateMultipartUploadOptions;
import com.robothy.s3.core.util.IdUtils;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.util.Checksums;
import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import com.robothy.s3.datatypes.enums.ChecksumType;
import java.util.Locale;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;

public interface CreateMultipartUploadService extends LocalS3MetadataApplicable {

  default String createMultipartUpload(String bucket, String key, CreateMultipartUploadOptions options) {
    return initiateMultipartUpload(bucket, key, options).getUploadId();
  }

  default CreateMultipartUploadAns initiateMultipartUpload(String bucket, String key,
                                                           CreateMultipartUploadOptions options) {
    ChecksumType checksumType = checksumType(options);
    ObjectLockAssertions.assertRequestedObjectLockIsValid(options.getObjectLock(), System.currentTimeMillis());
    return changeBucket(bucket, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucket);
      ObjectAssertions.assertObjectKeyIsWritable(key);
      ObjectAssertions.assertUserMetadataIsValid(options.getUserMetadata());
      if (options.getObjectLock() != null && !options.getObjectLock().isEmpty()) {
        ObjectLockAssertions.assertObjectLockEnabled(bucketMetadata);
      }
      String uploadId = IdUtils.defaultGenerator().nextStrId();
      // An upload that names no encryption stores its object with the default encryption of the bucket at the time it
      // is created, which its parts are answered with.
      ServerSideEncryption serverSideEncryption = Objects.isNull(options.getServerSideEncryption())
          && Objects.isNull(options.getCustomerEncryption())
          ? bucketMetadata.getDefaultEncryption() : options.getServerSideEncryption();
      NavigableMap<String, NavigableMap<String, UploadMetadata>> uploads = bucketMetadata.getUploads();
      uploads.putIfAbsent(key, new ConcurrentSkipListMap<>());
      uploads.get(key).put(uploadId, UploadMetadata.builder()
          .contentType(options.getContentType())
          .systemMetadata(options.getSystemMetadata())
          .createDate(System.currentTimeMillis())
          .tagging(options.getTagging().orElse(null))
          .userMetadata(options.getUserMetadata())
          .checksumAlgorithm(options.getChecksumAlgorithm())
          .checksumType(checksumType)
          .objectLock(options.getObjectLock())
          .customerEncryption(options.getCustomerEncryption())
          .serverSideEncryption(serverSideEncryption)
          .build());
      bucketMetadata.markUploadsChanged(key);
      return CreateMultipartUploadAns.builder()
          .uploadId(uploadId)
          .serverSideEncryption(serverSideEncryption)
          .build();
    });
  }

  private static ChecksumType checksumType(CreateMultipartUploadOptions options) {
    CheckSumAlgorithm algorithm = options.getChecksumAlgorithm();
    ChecksumType type = options.getChecksumType();
    if (Objects.isNull(algorithm)) {
      if (Objects.nonNull(type)) {
        throw new LocalS3RequestException(S3ErrorCode.InvalidRequest,
            "The x-amz-checksum-type header can only be used with the x-amz-checksum-algorithm header.");
      }
      return null;
    }
    if (Objects.isNull(type)) {
      return Checksums.defaultMultipartType(algorithm);
    }
    if (!Checksums.supports(algorithm, type)) {
      throw new LocalS3RequestException(S3ErrorCode.InvalidRequest, "The " + type + " checksum type cannot be used "
          + "with the " + algorithm.name().toLowerCase(Locale.ROOT) + " checksum algorithm.");
    }
    return type;
  }

}
