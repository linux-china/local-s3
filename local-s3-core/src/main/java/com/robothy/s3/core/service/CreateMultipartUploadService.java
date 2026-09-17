package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.ObjectAssertions;
import com.robothy.s3.core.assertions.ObjectLockAssertions;
import com.robothy.s3.core.model.internal.BucketMetadata;
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

  /**
   * Init a multipart upload.
   *
   * @param bucket  the bucket name
   * @param key     the object key.
   * @param options options of the multipart upload.
   * @return the upload ID.
   */
  default String createMultipartUpload(String bucket, String key, CreateMultipartUploadOptions options) {
    ChecksumType checksumType = checksumType(options);
    ObjectLockAssertions.assertRequestedObjectLockIsValid(options.getObjectLock(), System.currentTimeMillis());
    return changeBucket(bucket, () -> {
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucket);
      ObjectAssertions.assertObjectKeyIsValid(key);
      if (options.getObjectLock() != null && !options.getObjectLock().isEmpty()) {
        ObjectLockAssertions.assertObjectLockEnabled(bucketMetadata);
      }
      String uploadId = IdUtils.defaultGenerator().nextStrId();
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
          .build());
      bucketMetadata.markUploadsChanged(key);
      return uploadId;
    });
  }

  /**
   * The type of the checksum of the object that an upload stores.
   *
   * @return the type; {@code null} if the upload has no checksum algorithm.
   * @throws LocalS3RequestException {@code InvalidRequest} if the request names a type without an algorithm, or a
   *     type that the algorithm has no checksum of, like Amazon S3 rejects it.
   */
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
